#include "frame_engine.h"

#include "frame_guard_geometry.h"
#include "upstream/rife_warp.h"
#if NCNN_VULKAN
#include "frame_preproc.comp.hex.h"
#include "frame_postproc.comp.hex.h"
#include "frame_fused.comp.hex.h"
#include "frame_fused_rgb8.comp.hex.h"
#include "frame_reduce.comp.hex.h"
#include <pipeline.h>
#endif
#include <command.h>
#include <datareader.h>
#include <gpu.h>
#include <mat.h>
#include <net.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <sstream>
#include <optional>
#include <tuple>
#include <string>
#include <utility>
#include <vector>

namespace innocent::interpolation {
namespace {
constexpr std::size_t MaxParamBytes = 1024u * 1024u;
constexpr std::size_t MaxWeightBytes = 32u * 1024u * 1024u;
constexpr std::size_t MaxPixels = 4096u * 2160u;
constexpr int MaxDimension = 4096;

class BoundedReader final : public ncnn::DataReader {
public:
    BoundedReader(const std::uint8_t* bytes, std::size_t size) : bytes_(bytes), size_(size) {}
    std::size_t read(void* destination, std::size_t size) const override {
        if (size > size_ - offset_ || (size != 0 && destination == nullptr)) {
            truncated_ = true;
            return 0;
        }
        if (size != 0) std::memcpy(destination, bytes_ + offset_, size);
        offset_ += size;
        return size;
    }
    // Never expose borrowed weights as a retained model pointer; ncnn must copy through read.
    std::size_t reference(std::size_t, const void**) const override { return 0; }
    bool truncated() const noexcept { return truncated_; }
private:
    const std::uint8_t* bytes_;
    std::size_t size_;
    mutable std::size_t offset_ = 0;
    mutable bool truncated_ = false;
};

bool layoutValid(const void* data, int width, int height, std::size_t stride,
                 std::size_t capacity, std::size_t maxPixels) noexcept {
    if (data == nullptr || width <= 0 || height <= 0 || width > MaxDimension || height > MaxDimension)
        return false;
    const auto w = static_cast<std::size_t>(width);
    const auto h = static_cast<std::size_t>(height);
    if (w > maxPixels / h) return false;
    const auto rowBytes = w * 3u;
    if (stride < rowBytes || (h > 1 && stride > (std::numeric_limits<std::size_t>::max() - rowBytes) / (h - 1)))
        return false;
    return (h - 1) * stride + rowBytes <= capacity;
}

bool modelShapeValid(const ncnn::Net& net) {
    const auto& inputs = net.input_names();
    const auto& outputs = net.output_names();
    if (inputs.size() != 3 || outputs.size() != 1 || std::strcmp(outputs[0], "out0") != 0) return false;
    for (const char* required : {"in0", "in1", "in2"}) {
        bool found = false;
        for (const char* input : inputs) found = found || std::strcmp(required, input) == 0;
        if (!found) return false;
    }
    return true;
}

Status gpuReady(int index) {
#if NCNN_VULKAN
    static std::once_flag initialized;
    static int result = -1;
    std::call_once(initialized, [] { result = ncnn::create_gpu_instance(); });
    if (result != 0 || index < 0 || index >= ncnn::get_gpu_count() || ncnn::get_gpu_device(index) == nullptr)
        return {Code::GpuUnavailable};
    return {};
#else
    (void)index;
    return {Code::UnsupportedBackend};
#endif
}

ncnn::Mat inputTensor(ImageView image, int paddedWidth, int paddedHeight) {
    // NEON 路径的打包与归一化；补零到模型要求的倍数，与上游 v4 无 TTA 流程一致。
    ncnn::Mat packed = ncnn::Mat::from_pixels(image.data, ncnn::Mat::PIXEL_RGB, image.width, image.height,
                                              static_cast<int>(image.strideBytes));
    if (packed.empty()) return packed;
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    packed.substract_mean_normalize(nullptr, norm);
    if (paddedWidth == image.width && paddedHeight == image.height) return packed;
    ncnn::Mat padded;
    ncnn::copy_make_border(packed, padded, 0, paddedHeight - image.height, 0, paddedWidth - image.width,
                           ncnn::BORDER_CONSTANT, 0.f);
    return padded;
}

// 低分辨率光流（scale = 2 或 4）：GPU 下采样输入，flownet 只在 1/scale 分辨率上估计光流与融合遮罩，
// 再把光流（数值 ×scale）与遮罩上采样到全分辨率，用全分辨率原帧做 Warp 与融合。
// 末段与 flownet.param 的 warp_6/warp_7/mul_76/mul_77/add_78 同构：blob 327 为 4 通道光流，332 为遮罩。
// 运动置信度分块：与 frame_fused*.comp 的 GUARD_TILES_X/Y 一致。
// 分块常量与抽样几何见 frame_guard_geometry.h（过渡补丁，归 MeguvizRIFE）。

// 每个 16×9 分块在两帧间的抽样平均通道绝对差（约 320×180 网格）。
void tileMotion(const ImageView& a, const ImageView& b, std::array<double, GuardTiles>& out) {
    const int stepX = std::max(1, a.width / 320), stepY = std::max(1, a.height / 180);
    std::array<std::uint64_t, GuardTiles> sum{}, count{};
    for (int y = 0; y < a.height; y += stepY) {
        const auto* ra = a.data + static_cast<std::size_t>(y) * a.strideBytes;
        const auto* rb = b.data + static_cast<std::size_t>(y) * b.strideBytes;
        const int ty = y * GuardTilesY / a.height;
        for (int x = 0; x < a.width; x += stepX) {
            const int t = ty * GuardTilesX + x * GuardTilesX / a.width;
            for (int c = 0; c < 3; ++c)
                sum[t] += static_cast<std::uint64_t>(std::abs(static_cast<int>(ra[x * 3 + c]) - rb[x * 3 + c]));
            count[t] += 3;
        }
    }
    for (int t = 0; t < GuardTiles; ++t) out[t] = count[t] ? static_cast<double>(sum[t]) / count[t] : 0.0;
}

// 两帧颜色分布的差异（16 级 × 3 通道直方图的 L1 距离，0..1000）：切镜明显改变颜色构成，摇镜基本不变。
int histogramDistancePermille(const ImageView& a, const ImageView& b) {
    const int stepX = std::max(1, a.width / 320), stepY = std::max(1, a.height / 180);
    std::array<std::int64_t, 48> bins{};
    std::int64_t samples = 0;
    for (int y = 0; y < a.height; y += stepY) {
        const auto* ra = a.data + static_cast<std::size_t>(y) * a.strideBytes;
        const auto* rb = b.data + static_cast<std::size_t>(y) * b.strideBytes;
        for (int x = 0; x < a.width; x += stepX) {
            for (int c = 0; c < 3; ++c) { ++bins[c * 16 + (ra[x * 3 + c] >> 4)]; --bins[c * 16 + (rb[x * 3 + c] >> 4)]; }
            ++samples;
        }
    }
    std::int64_t l1 = 0;
    for (const auto v : bins) l1 += v < 0 ? -v : v;
    return samples ? static_cast<int>(l1 * 1000 / (6 * samples)) : 0;
}

// 约 320×180 网格的通道均值（0..255）；只读原图，成本与分辨率无关。
void sampleMeans(const ImageView& image, double out[3]) {
    const int stepX = std::max(1, image.width / 320), stepY = std::max(1, image.height / 180);
    std::uint64_t sum[3] = {0, 0, 0}, count = 0;
    for (int y = 0; y < image.height; y += stepY) {
        const auto* row = image.data + static_cast<std::size_t>(y) * image.strideBytes;
        for (int x = 0; x < image.width; x += stepX) {
            for (int c = 0; c < 3; ++c) sum[c] += row[static_cast<std::size_t>(x) * 3 + c];
            ++count;
        }
    }
    for (int c = 0; c < 3; ++c) out[c] = count ? static_cast<double>(sum[c]) / count : 0.0;
}

// adaptiveFlow：像素数超过此值的帧用 1/8 光流（1080p 及以下保持 1/4）。
constexpr std::int64_t AdaptiveFlowPixels = 1920LL * 1080LL;

std::string downscaleParam(int scale) {
    const std::string factor = std::to_string(1.0 / scale);
    return "7767517\n4 4\nInput in0 0 1 in0\nInput in1 0 1 in1\n"
           "Interp down0 1 1 in0 half0 0=2 1=" + factor + " 2=" + factor + "\n"
           "Interp down1 1 1 in1 half1 0=2 1=" + factor + " 2=" + factor + "\n";
}

std::string upscaleParam(int scale) {
    const std::string factor = std::to_string(static_cast<double>(scale));
    return "7767517\n17 19\n"
           "Input in0 0 1 in0\nInput in1 0 1 in1\nInput flow 0 1 flow\nInput mask 0 1 mask\n"
           "Interp upflow 1 1 flow flowup 0=2 1=" + factor + " 2=" + factor + "\n"
           "BinaryOp scaleflow 1 1 flowup flow2 0=2 1=1 2=" + factor + "\n"
           "Split splitflow 1 2 flow2 fa fb\n"
           "Crop flow0 1 1 fa f0 -23309=1,0 -23310=1,2 -23311=1,0\n"
           "Crop flow1 1 1 fb f1 -23309=1,2 -23310=1,4 -23311=1,0\n"
           "Interp upmask 1 1 mask maskup 0=2 1=" + factor + " 2=" + factor + "\n"
           "Split splitmask 1 2 maskup m0 m1\n"
           "BinaryOp invmask 1 1 m1 minv 0=7 1=1 2=1.0\n"
           "rife.Warp warp0 2 1 in0 f0 w0\nrife.Warp warp1 2 1 in1 f1 w1\n"
           "BinaryOp blend0 2 1 w0 m0 a 0=2\nBinaryOp blend1 2 1 w1 minv b 0=2\n"
           "BinaryOp merge 2 1 a b out0\n";
}

constexpr const char* FlowBlob = "327";
constexpr const char* MaskBlob = "332";

std::int64_t nowNs() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
} // namespace

class Engine::Impl {
public:
    explicit Impl(Options value) : options(value) {}
    ~Impl() { releaseNets(); releaseAllocators(); }
    Options options;
    std::mutex mutex;
    std::atomic<bool> loaded{false};
    bool fp16 = false;
    int flowScale = 1;
    bool gpuIo = false;
    bool protectStaticDetails = false;
    bool fusedWarp = false;
    bool rawGpuInput = false;
    bool adaptiveFlow = false;
    // 运动置信度保护（融合路径）：逐像素回退到未 warp 的时间加权混合，并统计不可靠像素比例。
    bool motionGuard = true;
    std::atomic<int> unreliablePermille{0};
    std::atomic<int> patchedTiles{0};
    std::atomic<int> histogramPermille{0};
    // 抽样几何只取决于处理尺寸；processRgb8 持有 mutex，单尺寸缓存无需额外同步。
    GuardGeometryCache guardGeometry;
    std::unique_ptr<ncnn::Net> network;
    std::unique_ptr<ncnn::Net> downscale;
    std::unique_ptr<ncnn::Net> upscale;
    // adaptiveFlow 时另备 1/6 与 1/8 光流的缩放网络（无权重），按 [scale] 索引；可逐次选择。
    std::array<std::unique_ptr<ncnn::Net>, 9> extraDown;
    std::array<std::unique_ptr<ncnn::Net>, 9> extraUp;
    std::vector<std::uint8_t> staging;
    std::atomic<std::int64_t> timings[3] = {};
#if NCNN_VULKAN
    // 常驻显存池：避免每次推理重新申请/释放 VkBuffer；网络必须先于池释放。
    ncnn::VulkanDevice* device = nullptr;
    ncnn::VkAllocator* blobAllocator = nullptr;
    ncnn::VkAllocator* stagingAllocator = nullptr;
    // 每个源帧会在相邻两次插帧中各作一次输入（后一帧 → 下一次的前一帧）。按帧 ID 缓存其显存张量，
    // 省去重复的上传、解包与下采样；帧 ID 由所有权账本保证唯一且永不复用，内容在其存续期内不变。
    struct GpuFrame {
        std::int64_t id = -1;
        int width = 0, height = 0;
        // Only the explicit rawGpuInput experiment retains RGB8; the default retains planar input.
        ncnn::VkMat full, reduced;
        int sourceWidth = 0, sourceHeight = 0;
        std::size_t sourceStride = 0;
        // 下采样输入的光流比例；不同比例的 reduced 尺寸不同，缓存命中必须一致。
        int scale = 0;
    };
    GpuFrame frames[3];
    int nextFrame = 0;
    void clearFrames() { for (auto& frame : frames) frame = GpuFrame{}; nextFrame = 0; }
    // RGB8 <-> 平面浮点的 GPU 转换管线；与网络同一 Option 编译，fp16 存储保持一致。
    std::unique_ptr<ncnn::Pipeline> preproc;
    std::unique_ptr<ncnn::Pipeline> postproc;
    std::unique_ptr<ncnn::Pipeline> fused;
    std::unique_ptr<ncnn::Pipeline> reduce;
#endif

    void releaseNets() noexcept {
#if NCNN_VULKAN
        clearFrames();
        reduce.reset(); fused.reset(); postproc.reset(); preproc.reset();
#endif
        for (auto& net : extraUp) net.reset();
        for (auto& net : extraDown) net.reset();
        upscale.reset(); downscale.reset(); network.reset();
    }
    void releaseAllocators() noexcept {
#if NCNN_VULKAN
        if (device && blobAllocator) device->reclaim_blob_allocator(blobAllocator);
        if (device && stagingAllocator) device->reclaim_staging_allocator(stagingAllocator);
        blobAllocator = stagingAllocator = nullptr;
#endif
    }
    void configureNet(ncnn::Net& net, bool fp16Arithmetic = true) const {
        net.opt.num_threads = options.threads;
        net.opt.use_vulkan_compute = options.backend == Backend::Vulkan;
        // fp16 仅在显式开启时启用，并由 ncnn 按设备能力再与运算；默认保持 fp32 参考路径。
        net.opt.use_fp16_packed = fp16;
        net.opt.use_fp16_storage = fp16;
        net.opt.use_fp16_arithmetic = fp16 && fp16Arithmetic;
        net.opt.use_bf16_storage = false;
        net.opt.use_int8_inference = false;
#if NCNN_VULKAN
        if (options.backend == Backend::Vulkan) {
            net.set_vulkan_device(options.gpuIndex);
            net.opt.blob_vkallocator = blobAllocator;
            net.opt.workspace_vkallocator = blobAllocator;
            net.opt.staging_vkallocator = stagingAllocator;
        }
#endif
    }
};

const char* Status::name() const noexcept {
    switch (code) {
        case Code::Ok: return "ok";
        case Code::InvalidArgument: return "invalid_argument";
        case Code::UnsupportedBackend: return "unsupported_backend";
        case Code::GpuUnavailable: return "gpu_unavailable";
        case Code::ModelTooLarge: return "model_too_large";
        case Code::InvalidModel: return "invalid_model";
        case Code::ModelLoadFailed: return "model_load_failed";
        case Code::NotLoaded: return "not_loaded";
        case Code::Busy: return "busy";
        case Code::AllocationFailed: return "allocation_failed";
        case Code::InferenceFailed: return "inference_failed";
        case Code::InvalidOutput: return "invalid_output";
    }
    return "unknown";
}

Engine::Engine(std::unique_ptr<Impl> impl) noexcept : impl_(std::move(impl)) {}
Engine::~Engine() = default;

std::unique_ptr<Engine> Engine::create(const Options& options, Status* status) noexcept {
    const auto fail = [status](Code code) -> std::unique_ptr<Engine> {
        if (status != nullptr) *status = {code};
        return nullptr;
    };
    try {
        if (options.threads < 1 || options.threads > 8 || options.maxPixels == 0 || options.maxPixels > MaxPixels)
            return fail(Code::InvalidArgument);
        if (options.backend != Backend::Cpu && options.backend != Backend::Vulkan) return fail(Code::UnsupportedBackend);
        if (options.backend == Backend::Vulkan) {
            const Status gpu = gpuReady(options.gpuIndex);
            if (!gpu.ok()) return fail(gpu.code);
        }
        auto engine = std::unique_ptr<Engine>(new Engine(std::make_unique<Impl>(options)));
        if (status != nullptr) *status = {};
        return engine;
    } catch (const std::bad_alloc&) { return fail(Code::AllocationFailed); }
    catch (...) { return fail(Code::InferenceFailed); }
}

Status Engine::configure(bool fp16, int flowScale, bool gpuIo, bool protectStaticDetails, bool fusedWarp, bool rawGpuInput,
                         bool adaptiveFlow) noexcept {
    std::unique_lock<std::mutex> guard(impl_->mutex, std::try_to_lock);
    if (!guard.owns_lock()) return {Code::Busy};
    if (flowScale != 1 && flowScale != 2 && flowScale != 4 && flowScale != 8) return {Code::InvalidArgument};
    if (protectStaticDetails && !gpuIo) return {Code::InvalidArgument};
    if (fusedWarp && (!gpuIo || flowScale == 1)) return {Code::InvalidArgument};
    if (rawGpuInput && !fusedWarp) return {Code::InvalidArgument};
    if (adaptiveFlow && flowScale != 4) return {Code::InvalidArgument};
    if ((flowScale != 1 || gpuIo) && impl_->options.backend != Backend::Vulkan) return {Code::UnsupportedBackend};
    // 精度在 load 时固化进管线：改配置即撤销已加载模型，须重新 load。
    impl_->loaded.store(false);
    impl_->releaseNets();
    impl_->fp16 = fp16;
    impl_->flowScale = flowScale;
    impl_->gpuIo = gpuIo;
    impl_->protectStaticDetails = protectStaticDetails;
    impl_->fusedWarp = fusedWarp;
    impl_->rawGpuInput = rawGpuInput;
    impl_->adaptiveFlow = adaptiveFlow;
    return {};
}

int Engine::unreliablePermille() const noexcept { return impl_->unreliablePermille.load(); }

int Engine::patchedTiles() const noexcept { return impl_->patchedTiles.load(); }

int Engine::histogramPermille() const noexcept { return impl_->histogramPermille.load(); }

void Engine::setMotionGuard(bool enabled) noexcept { impl_->motionGuard = enabled; }

void Engine::timings(std::int64_t out[3]) const noexcept {
    for (int i = 0; i < 3; ++i) out[i] = impl_->timings[i].load();
}

Status Engine::load(ModelView model) noexcept {
    try {
        std::unique_lock<std::mutex> guard(impl_->mutex, std::try_to_lock);
        if (!guard.owns_lock()) return {Code::Busy};
        impl_->loaded.store(false);
        impl_->releaseNets();
        if (model.param == nullptr || model.weights == nullptr || model.paramSize == 0 || model.weightsSize == 0)
            return {Code::InvalidArgument};
        if (model.paramSize > MaxParamBytes || model.weightsSize > MaxWeightBytes) return {Code::ModelTooLarge};
        if (std::memchr(model.param, 0, model.paramSize) != nullptr) return {Code::InvalidModel};
        // load_param_mem needs a NUL terminator; the caller's buffer need not provide one.
        const std::string param(reinterpret_cast<const char*>(model.param), model.paramSize);
        int magic = 0, layers = 0, blobs = 0;
        std::istringstream header(param);
        if (!(header >> magic >> layers >> blobs) || magic != 7767517 || layers <= 0 || layers > 1024 || blobs <= 0 || blobs > 8192)
            return {Code::InvalidModel};
#if NCNN_VULKAN
        if (impl_->options.backend == Backend::Vulkan && impl_->blobAllocator == nullptr) {
            impl_->device = ncnn::get_gpu_device(impl_->options.gpuIndex);
            if (impl_->device == nullptr) return {Code::GpuUnavailable};
            impl_->blobAllocator = impl_->device->acquire_blob_allocator();
            impl_->stagingAllocator = impl_->device->acquire_staging_allocator();
            if (impl_->blobAllocator == nullptr || impl_->stagingAllocator == nullptr) return {Code::AllocationFailed};
        }
#endif
        auto network = std::make_unique<ncnn::Net>();
        impl_->configureNet(*network);
        if (network->register_custom_layer("rife.Warp", detail::createWarp) != 0 ||
            network->load_param_mem(param.c_str()) != 0 || !modelShapeValid(*network)) return {Code::InvalidModel};
        BoundedReader weights(model.weights, model.weightsSize);
        const int loaded = network->load_model(weights);
        if (loaded != 0 || weights.truncated()) return {Code::ModelLoadFailed};
#if NCNN_VULKAN
        if (impl_->options.backend == Backend::Vulkan) {
            if (!network->opt.use_vulkan_compute || network->vulkan_device() == nullptr) return {Code::GpuUnavailable};
            for (const auto* layer : network->layers()) {
                if (layer == nullptr || (!layer->support_vulkan && layer->type != "Input" && layer->type != "Split"))
                    return {Code::UnsupportedBackend};
            }
        }
#endif
        for (const int scale : {impl_->flowScale, impl_->adaptiveFlow ? 6 : 1, impl_->adaptiveFlow ? 8 : 1}) {
            if (scale == 1) continue;
            auto downscale = std::make_unique<ncnn::Net>();
            auto upscale = std::make_unique<ncnn::Net>();
            // 下采样只做 Interp：fp16 运算下源坐标超过 2048 时间距为 2 像素，4K 输入会错位取样；改用 fp32 运算
            // （存储仍为 fp16），代价可忽略。
            impl_->configureNet(*downscale, false);
            impl_->configureNet(*upscale);
            const BoundedReader empty(nullptr, 0);
            const std::string downParam = downscaleParam(scale);
            const std::string upParam = upscaleParam(scale);
            if (downscale->load_param_mem(downParam.c_str()) != 0 || downscale->load_model(empty) != 0 ||
                upscale->register_custom_layer("rife.Warp", detail::createWarp) != 0 ||
                upscale->load_param_mem(upParam.c_str()) != 0 || upscale->load_model(empty) != 0)
                return {Code::ModelLoadFailed};
            (scale == impl_->flowScale ? impl_->downscale : impl_->extraDown[scale]) = std::move(downscale);
            (scale == impl_->flowScale ? impl_->upscale : impl_->extraUp[scale]) = std::move(upscale);
        }
#if NCNN_VULKAN
        if (impl_->gpuIo) {
            struct Source { const char* data; int size; std::unique_ptr<ncnn::Pipeline>* slot; int x, y, z; };
            const Source sources[] = {
                {frame_preproc_comp_data, sizeof(frame_preproc_comp_data)-1, &impl_->preproc, 8, 8, 1},
                {frame_postproc_comp_data, sizeof(frame_postproc_comp_data)-1, &impl_->postproc, 64, 1, 1},
                {frame_reduce_comp_data, sizeof(frame_reduce_comp_data)-1, &impl_->reduce, 8, 8, 1}};
            for (const auto& source : sources) {
                if (source.slot == &impl_->reduce && !impl_->rawGpuInput) continue;
                std::vector<std::uint32_t> spirv;
                if (ncnn::compile_spirv_module(source.data, source.size, network->opt, spirv) != 0 || spirv.empty())
                    return {Code::ModelLoadFailed};
                auto pipeline = std::make_unique<ncnn::Pipeline>(impl_->device);
                pipeline->set_optimal_local_size_xyz(source.x, source.y, source.z);
                if (pipeline->create(spirv.data(), spirv.size() * sizeof(std::uint32_t), {}) != 0)
                    return {Code::ModelLoadFailed};
                *source.slot = std::move(pipeline);
            }
            if (impl_->fusedWarp) {
                std::vector<std::uint32_t> spirv;
                const char* source = impl_->rawGpuInput ? frame_fused_rgb8_comp_data : frame_fused_comp_data;
                const int size = impl_->rawGpuInput ? sizeof(frame_fused_rgb8_comp_data)-1 : sizeof(frame_fused_comp_data)-1;
                if (ncnn::compile_spirv_module(source, size, network->opt, spirv) != 0)
                    return {Code::ModelLoadFailed};
                impl_->fused = std::make_unique<ncnn::Pipeline>(impl_->device);
                impl_->fused->set_optimal_local_size_xyz(64, 1, 1);
                if (spirv.empty() || impl_->fused->create(spirv.data(), spirv.size()*sizeof(std::uint32_t), {}) != 0)
                    return {Code::ModelLoadFailed};
            }
        }
#endif
        impl_->network = std::move(network);
        impl_->loaded.store(true);
        return {};
    } catch (const std::bad_alloc&) { return {Code::AllocationFailed}; }
    catch (...) { return {Code::ModelLoadFailed}; }
}

Status Engine::processRgb8(ImageView left, ImageView right, float timestep, MutableImageView output,
                           std::int64_t leftId, std::int64_t rightId, int flowScale) noexcept {
    try {
        std::unique_lock<std::mutex> guard(impl_->mutex, std::try_to_lock);
        if (!guard.owns_lock()) return {Code::Busy};
        if (!impl_->loaded.load() || !impl_->network) return {Code::NotLoaded};
        if (!std::isfinite(timestep) || timestep < 0.f || timestep > 1.f ||
            left.width != right.width || left.height != right.height || left.width != output.width || left.height != output.height ||
            !layoutValid(left.data, left.width, left.height, left.strideBytes, left.byteCapacity, impl_->options.maxPixels) ||
            !layoutValid(right.data, right.width, right.height, right.strideBytes, right.byteCapacity, impl_->options.maxPixels) ||
            !layoutValid(output.data, output.width, output.height, output.strideBytes, output.byteCapacity, impl_->options.maxPixels))
            return {Code::InvalidArgument};
        const auto rowBytes = static_cast<std::size_t>(left.width) * 3u;
        if (timestep == 0.f || timestep == 1.f || !impl_->gpuIo)
            impl_->staging.resize(rowBytes * static_cast<std::size_t>(left.height));
        if (timestep == 0.f || timestep == 1.f) {
            const auto image = timestep == 0.f ? left : right;
            for (int y = 0; y < image.height; ++y)
                std::memcpy(impl_->staging.data() + static_cast<std::size_t>(y) * rowBytes,
                            image.data + static_cast<std::size_t>(y) * image.strideBytes, rowBytes);
        } else {
            const auto began = nowNs();
            impl_->unreliablePermille.store(0);
            impl_->patchedTiles.store(0);
            impl_->histogramPermille.store(0);
            // 全分辨率补到 32×scale 的倍数，低分辨率恰为 32 的倍数，上下采样严格 scale 倍。
            // 光流比例：调用方显式指定（仅自适应模式，须为已载入的 4/6/8），否则按像素数自动选择。
            int scale = impl_->flowScale;
            if (impl_->adaptiveFlow)
                scale = flowScale > 0 ? flowScale
                    : static_cast<std::int64_t>(left.width) * left.height > AdaptiveFlowPixels ? 8 : impl_->flowScale;
            else if (flowScale > 0 && flowScale != impl_->flowScale) return {Code::InvalidArgument};
            const bool extra = scale != impl_->flowScale;
            if (extra && (scale < 0 || scale > 8 || !impl_->extraDown[scale])) return {Code::InvalidArgument};
            ncnn::Net* const downNet = extra ? impl_->extraDown[scale].get() : impl_->downscale.get();
            ncnn::Net* const upNet = extra ? impl_->extraUp[scale].get() : impl_->upscale.get();
            const int align = 32 * scale;
            const int paddedWidth = (left.width + align - 1) / align * align;
            const int paddedHeight = (left.height + align - 1) / align * align;
            ncnn::Mat time(paddedWidth / scale, paddedHeight / scale, 1);
            if (time.empty()) return {Code::AllocationFailed};
            time.fill(timestep);
            const bool commandPath = scale != 1 || impl_->gpuIo;
            ncnn::Mat first, second;
            if (!impl_->gpuIo) {
                first = inputTensor(left, paddedWidth, paddedHeight);
                second = inputTensor(right, paddedWidth, paddedHeight);
                if (first.empty() || second.empty()) return {Code::AllocationFailed};
            }
            auto prepared = nowNs();
            auto inferred = prepared;
            ncnn::Mat result;
            if (!commandPath) {
                ncnn::Extractor extractor = impl_->network->create_extractor();
                if (extractor.input("in0", first) != 0 || extractor.input("in1", second) != 0 ||
                    extractor.input("in2", time) != 0 || extractor.extract("out0", result) != 0)
                    return {Code::InferenceFailed};
            } else {
#if NCNN_VULKAN
                // 一条命令缓冲：每个输入只上传一次，中间结果全程留在显存，只下载最终输出。
                const ncnn::Option& opt = impl_->network->opt;
                ncnn::VkCompute command(impl_->device);
                ncnn::VkMat full0, full1, flowInput0, flowInput1, merged, flow, mask;
                std::vector<ncnn::Mat> keepAlive;
                // 各 Extractor 持有其 VkMat，必须存活到 submit_and_wait 之后。
                std::vector<ncnn::Extractor> downs;
                std::optional<ncnn::Extractor> up;
                std::vector<Impl::GpuFrame> fresh;
                if (impl_->gpuIo) {
                    // 只上传原始 RGB8 字节；解包、归一化与补零在 GPU 完成。缓存命中的帧完全不上传。
                    const std::size_t elem = opt.use_fp16_storage ? 2u : 4u;
                    for (const auto& [image, id, target, reduced] : {std::tuple{left, leftId, &full0, &flowInput0},
                                                                     std::tuple{right, rightId, &full1, &flowInput1}}) {
                        const Impl::GpuFrame* hit = nullptr;
                        for (const auto& frame : impl_->frames)
                            if (id >= 0 && frame.id == id && frame.scale == scale && frame.width == paddedWidth && frame.height == paddedHeight &&
                                frame.sourceWidth == image.width && frame.sourceHeight == image.height &&
                                frame.sourceStride == image.strideBytes) hit = &frame;
                        if (hit != nullptr) { *target = hit->full; *reduced = hit->reduced; continue; }
                        const std::size_t bytes = (static_cast<std::size_t>(image.height) - 1) * image.strideBytes +
                                                  static_cast<std::size_t>(image.width) * 3u;
                        const int words = static_cast<int>((bytes + 3) / 4);
                        ncnn::Mat raw;
                        if (bytes % 4 == 0 && reinterpret_cast<std::uintptr_t>(image.data) % 4 == 0) {
                            raw = ncnn::Mat(words, const_cast<std::uint8_t*>(image.data), static_cast<std::size_t>(4u));
                        } else {
                            raw.create(words, static_cast<std::size_t>(4u));
                            if (raw.empty()) return {Code::AllocationFailed};
                            static_cast<std::uint32_t*>(raw.data)[words - 1] = 0;
                            std::memcpy(raw.data, image.data, bytes);
                        }
                        keepAlive.push_back(raw);
                        ncnn::VkMat rawGpu;
                        command.record_clone(raw, rawGpu, opt);
                        if (impl_->rawGpuInput) {
                            *target = rawGpu;
                            reduced->create(paddedWidth/scale, paddedHeight/scale, 3, elem, 1, impl_->blobAllocator);
                            if (rawGpu.empty() || reduced->empty()) return {Code::AllocationFailed};
                            std::vector<ncnn::vk_constant_type> constants(7);
                            constants[0].i=image.width; constants[1].i=image.height;
                            constants[2].i=static_cast<int>(image.strideBytes);
                            constants[3].i=reduced->w; constants[4].i=reduced->h;
                            constants[5].i=static_cast<int>(reduced->cstep); constants[6].i=scale;
                            command.record_pipeline(impl_->reduce.get(), {rawGpu,*reduced}, constants, *reduced);
                        } else {
                            target->create(paddedWidth, paddedHeight, 3, elem, 1, impl_->blobAllocator);
                            if (rawGpu.empty() || target->empty()) return {Code::AllocationFailed};
                            std::vector<ncnn::vk_constant_type> constants(6);
                            constants[0].i = image.width;
                            constants[1].i = image.height;
                            constants[2].i = static_cast<int>(image.strideBytes);
                            constants[3].i = target->w;
                            constants[4].i = target->h;
                            constants[5].i = static_cast<int>(target->cstep);
                            command.record_pipeline(impl_->preproc.get(), {rawGpu, *target}, constants, *target);
                            *reduced = *target;
                            if (scale != 1) {
                                downs.push_back(downNet->create_extractor());
                                downs.back().set_light_mode(false);
                                if (downs.back().input("in0", *target) != 0 || downs.back().extract("half0", *reduced, command) != 0)
                                    return {Code::InferenceFailed};
                            }
                        }
                        if (id >= 0) fresh.push_back({id, paddedWidth, paddedHeight, *target, *reduced,
                            image.width, image.height, image.strideBytes, scale});
                    }
                } else {
                    command.record_upload(first, full0, opt);
                    command.record_upload(second, full1, opt);
                    flowInput0 = full0;
                    flowInput1 = full1;
                    if (scale != 1) {
                        downs.push_back(downNet->create_extractor());
                        auto& down = downs.back();
                        down.set_light_mode(false);
                        if (down.input("in0", full0) != 0 || down.input("in1", full1) != 0 ||
                            down.extract("half0", flowInput0, command) != 0 || down.extract("half1", flowInput1, command) != 0)
                            return {Code::InferenceFailed};
                    }
                }
                prepared = nowNs();
                ncnn::Extractor estimate = impl_->network->create_extractor();
                // Vulkan records resource lifetimes/barriers; extracted flow/mask keep their own references.
                // Reclaim dead internal activations instead of retaining the whole graph until completion.
                estimate.set_light_mode(impl_->fusedWarp);
                if (estimate.input("in0", flowInput0) != 0 || estimate.input("in1", flowInput1) != 0 ||
                    estimate.input("in2", time) != 0) return {Code::InferenceFailed};
                if (scale == 1) {
                    if (estimate.extract("out0", merged, command) != 0) return {Code::InferenceFailed};
                } else {
                    if (estimate.extract(FlowBlob, flow, command) != 0 || estimate.extract(MaskBlob, mask, command) != 0)
                        return {Code::InferenceFailed};
                    if (!impl_->fusedWarp) {
                        up.emplace(upNet->create_extractor());
                        if (up->input("in0", full0) != 0 || up->input("in1", full1) != 0 || up->input("flow", flow) != 0 ||
                            up->input("mask", mask) != 0 || up->extract("out0", merged, command) != 0)
                            return {Code::InferenceFailed};
                    }
                }
                if (!impl_->fusedWarp && (merged.dims != 3 || merged.w != paddedWidth || merged.h != paddedHeight || merged.c * merged.elempack != 3))
                    return {Code::InvalidOutput};
                if (impl_->gpuIo) {
                    // GPU 上缩放、饱和取整并紧凑打包为 RGB8，同时标记任何非有限值；只下载字节与标志。
                    ncnn::VkMat planar = merged;
                    if (!impl_->fusedWarp && merged.elempack != 1) impl_->device->convert_packing(merged, planar, 1, command, opt);
                    const int words = static_cast<int>((rowBytes * static_cast<std::size_t>(left.height) + 3) / 4);
                    ncnn::VkMat packed, flag;
                    packed.create(words, static_cast<std::size_t>(4u), 1, impl_->blobAllocator);
                    // [0] 非有限值标志，[1] 运动置信度不足的抽样像素数，[2..] 同一计数按 16×9 分块（仅融合路径）。
                    ncnn::Mat zero(2 + GuardTiles, static_cast<std::size_t>(4u));
                    if (packed.empty() || zero.empty()) return {Code::AllocationFailed};
                    std::memset(zero.data, 0, (2 + GuardTiles) * sizeof(std::uint32_t));
                    keepAlive.push_back(zero);
                    command.record_clone(zero, flag, opt);
                    std::vector<ncnn::vk_constant_type> constants(impl_->fusedWarp ? (impl_->rawGpuInput ? 21 : 20) : 7);
                    constants[0].i = left.width;
                    constants[1].i = left.height;
                    constants[2].i = words;
                    constants[3].i = paddedWidth;
                    if (impl_->fusedWarp) {
                        if (flow.w != paddedWidth/scale || flow.h != paddedHeight/scale || flow.c*flow.elempack != 4 ||
                            mask.w != flow.w || mask.h != flow.h || mask.c*mask.elempack != 1 ||
                            (flow.elempack != 1 && flow.elempack != 4)) return {Code::InvalidOutput};
                        constants[4].i = static_cast<int>(impl_->rawGpuInput ? left.strideBytes : full0.cstep);
                        constants[5].i = impl_->protectStaticDetails ? 1 : 0;
                        constants[6].i = paddedHeight;
                        constants[7].i = flow.w; constants[8].i = flow.h;
                        constants[9].i = static_cast<int>(flow.cstep);
                        constants[10].i = flow.elempack; constants[11].i = scale;
                        if (impl_->rawGpuInput) constants[12].i = static_cast<int>(right.strideBytes);
                        const int extra = impl_->rawGpuInput ? 13 : 12;
                        constants[extra].f = timestep;
                        constants[extra + 1].i = impl_->motionGuard ? 1 : 0;
                        // 整帧亮度变化（闪白、淡入淡出）：抽样通道均值的偏移与增益，供置信度比较时扣除。
                        double meanL[3] = {0, 0, 0}, meanR[3] = {0, 0, 0};
                        sampleMeans(left, meanL);
                        sampleMeans(right, meanR);
                        for (int c = 0; c < 3; ++c) {
                            constants[extra + 2 + c].f = static_cast<float>((meanR[c] - meanL[c]) / 255.0);
                            constants[extra + 5 + c].f = static_cast<float>(meanL[c] > 8.0 ? std::clamp(meanR[c] / meanL[c], 0.0, 8.0) : 1.0);
                        }
                        ncnn::VkMat dispatcher;
                        dispatcher.dims = 1; dispatcher.w = (left.width*left.height+3)/4; dispatcher.h = dispatcher.c = 1;
                        command.record_pipeline(impl_->fused.get(), {flow, packed, flag, full0, full1, mask}, constants, dispatcher);
                    } else {
                        constants[4].i = static_cast<int>(planar.cstep);
                        constants[5].i = static_cast<int>(full0.cstep);
                        constants[6].i = impl_->protectStaticDetails ? 1 : 0;
                        command.record_pipeline(impl_->postproc.get(), {planar, packed, flag, full0, full1}, constants, packed);
                    }
                    ncnn::Mat bytes, flagged;
                    command.record_clone(packed, bytes, opt);
                    command.record_clone(flag, flagged, opt);
                    if (command.submit_and_wait() != 0) return {Code::InferenceFailed};
                    inferred = nowNs();
                    // 只有整条命令成功后才把新帧放进缓存，失败路径不会留下未写入的显存张量。
                    for (auto& frame : fresh) {
                        impl_->frames[impl_->nextFrame] = frame;
                        impl_->nextFrame = (impl_->nextFrame + 1) % 3;
                    }
                    if (flagged.empty() || bytes.empty() || static_cast<const std::uint32_t*>(flagged.data)[0] != 0)
                        return {Code::InvalidOutput};
                    std::array<bool, GuardTiles> patch{};
                    if (impl_->fusedWarp && impl_->motionGuard) {
                        // 抽样归属只由尺寸决定：按尺寸缓存，同尺寸连续帧不再逐帧重算；
                        // 没有 patch 时不计算 tileMotion（决策见 frame_guard_geometry.h）。
                        const auto* counts = static_cast<const std::uint32_t*>(flagged.data);
                        const auto& geometry = impl_->guardGeometry.get(left.width, left.height);
                        const TileDecision decision = decideTilePatches(counts, geometry,
                            [&](std::array<double, GuardTiles>& motion) { tileMotion(left, right, motion); });
                        impl_->unreliablePermille.store(decision.unreliablePermille);
                        patch = decision.patch;
                        impl_->histogramPermille.store(histogramDistancePermille(left, right));
                    }
                    // No output writes until fence completion and the finite-value flag are validated.
                    // The download is already a stable tight RGB8 image; avoid an extra 4K staging copy.
                    for (int y = 0; y < output.height; ++y)
                        std::memcpy(output.data + static_cast<std::size_t>(y)*output.strideBytes,
                            static_cast<const std::uint8_t*>(bytes.data)+static_cast<std::size_t>(y)*rowBytes,rowBytes);
                    int patched = 0;
                    const ImageView& nearest = timestep <= 0.5f ? left : right;
                    for (int t = 0; t < GuardTiles; ++t) {
                        if (!patch[t]) continue;
                        ++patched;
                        // 分块范围与着色器的 x*16/width 归属一致。
                        const int tx = t % GuardTilesX, ty = t / GuardTilesX;
                        const int x0 = (tx * output.width + GuardTilesX - 1) / GuardTilesX;
                        const int x1 = ((tx + 1) * output.width + GuardTilesX - 1) / GuardTilesX;
                        const int y0 = (ty * output.height + GuardTilesY - 1) / GuardTilesY;
                        const int y1 = ((ty + 1) * output.height + GuardTilesY - 1) / GuardTilesY;
                        for (int y = y0; y < y1; ++y)
                            std::memcpy(output.data + static_cast<std::size_t>(y) * output.strideBytes + static_cast<std::size_t>(x0) * 3,
                                nearest.data + static_cast<std::size_t>(y) * nearest.strideBytes + static_cast<std::size_t>(x0) * 3,
                                static_cast<std::size_t>(x1 - x0) * 3);
                    }
                    impl_->patchedTiles.store(patched);
                    impl_->timings[0].store(prepared-began);
                    impl_->timings[1].store(inferred-prepared);
                    impl_->timings[2].store(nowNs()-inferred);
                    return {};
                } else {
                    ncnn::Mat downloaded;
                    command.record_download(merged, downloaded, opt);
                    if (command.submit_and_wait() != 0) return {Code::InferenceFailed};
                    ncnn::Mat unpacked = downloaded;
                    if (downloaded.elempack != 1) ncnn::convert_packing(downloaded, unpacked, 1, opt);
                    if (unpacked.elembits() == 16) ncnn::cast_float16_to_float32(unpacked, result, opt);
                    else result = unpacked;
                }
#else
                return {Code::UnsupportedBackend};
#endif
            }
            if (!impl_->gpuIo) {
                inferred = nowNs();
                if (result.empty() || result.dims != 3 || result.w != paddedWidth || result.h != paddedHeight ||
                    result.c != 3 || result.elempack != 1 || result.elemsize != sizeof(float)) return {Code::InvalidOutput};
                // 裁掉补边后整平面检查有限值，再用 ncnn 的 NEON 路径缩放、饱和取整并打包为 RGB8。
                ncnn::Mat visible = result;
                if (paddedWidth != left.width || paddedHeight != left.height)
                    ncnn::copy_cut_border(result, visible, 0, paddedHeight - left.height, 0, paddedWidth - left.width);
                if (visible.empty() || visible.w != left.width || visible.h != left.height) return {Code::InvalidOutput};
                const auto planeSize = static_cast<std::size_t>(left.width) * static_cast<std::size_t>(left.height);
                for (int c = 0; c < 3; ++c) {
                    const float* plane = visible.channel(c);
                    bool finite = true;
                    for (std::size_t i = 0; i < planeSize; ++i) finite &= std::isfinite(plane[i]);
                    if (!finite) return {Code::InvalidOutput};
                }
                const float factor[3] = {255.f, 255.f, 255.f};
                visible.substract_mean_normalize(nullptr, factor);
                visible.to_pixels(impl_->staging.data(), ncnn::Mat::PIXEL_RGB, static_cast<int>(rowBytes));
            }
            impl_->timings[0].store(prepared - began);
            impl_->timings[1].store(inferred - prepared);
            impl_->timings[2].store(nowNs() - inferred);
        }
        // Commit only a complete valid result; error paths preserve output, including aliased inputs.
        for (int y = 0; y < output.height; ++y)
            std::memcpy(output.data + static_cast<std::size_t>(y) * output.strideBytes,
                        impl_->staging.data() + static_cast<std::size_t>(y) * rowBytes, rowBytes);
        return {};
    } catch (const std::bad_alloc&) { return {Code::AllocationFailed}; }
    catch (...) { return {Code::InferenceFailed}; }
}

Backend Engine::backend() const noexcept { return impl_->options.backend; }
bool Engine::ready() const noexcept { return impl_->loaded.load(); }
} // namespace innocent::interpolation
