#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <new>

namespace {

constexpr jlong kFlagVulkanAvailable = 1LL << 0;
constexpr jlong kFlagVulkanCompute = 1LL << 1;
constexpr jlong kFlagQcomBlockMatch = 1LL << 2;
constexpr jlong kFlagQcomAhb = 1LL << 3;
constexpr jlong kFlagVulkanAhb = 1LL << 4;
constexpr jlong kFlagVulkanSyncFd = 1LL << 5;
constexpr jlong kFlagShaderFp16 = 1LL << 6;
constexpr jlong kFlagSubgroupOps = 1LL << 7;
constexpr jlong kFlagCoopMatrix = 1LL << 8;
constexpr jlong kFlagDotProduct = 1LL << 9;

constexpr uint32_t kQcomMotionWidth = 480;
constexpr uint32_t kQcomMotionHeight = 272;
constexpr uint32_t kQcomMotionTile = 8;
constexpr uint32_t kQcomPackedLumaWidth = kQcomMotionWidth / 4;

template <typename T>
class ScopedArray {
public:
    explicit ScopedArray(uint32_t count)
        : data_(count > 0 ? new (std::nothrow) T[count]() : nullptr), count_(data_ ? count : 0) {}
    ~ScopedArray() { delete[] data_; }
    ScopedArray(const ScopedArray&) = delete;
    ScopedArray& operator=(const ScopedArray&) = delete;

    T* data() { return data_; }
    const T* data() const { return data_; }
    uint32_t size() const { return count_; }
    bool empty() const { return count_ == 0 || data_ == nullptr; }
    const T& operator[](uint32_t i) const { return data_[i]; }

private:
    T* data_;
    uint32_t count_;
};

PFN_vkGetInstanceProcAddr loadVkGetInstanceProcAddr() {
    // Keep libvulkan.so resident once opened so vendor ICDs never tear down TLS on dlclose.
    static void* vulkanLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vulkanLib) return nullptr;
    return reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vulkanLib, "vkGetInstanceProcAddr"));
}

bool hasExtension(const ScopedArray<VkExtensionProperties>& extensions, const char* name) {
    for (uint32_t i = 0; i < extensions.size(); ++i) {
        if (std::strcmp(extensions[i].extensionName, name) == 0) return true;
    }
    return false;
}

template <typename Pfn>
Pfn instanceProc(PFN_vkGetInstanceProcAddr getProc, VkInstance instance, const char* name) {
    return reinterpret_cast<Pfn>(getProc(instance, name));
}

bool supportsAhbImport(const ScopedArray<VkExtensionProperties>& extensions,
                       PFN_vkGetPhysicalDeviceImageFormatProperties2 getImageFormat2,
                       VkPhysicalDevice physical,
                       VkImageUsageFlags usage,
                       uint32_t minWidth,
                       uint32_t minHeight) {
    if (!getImageFormat2) return false;
    if (!hasExtension(extensions, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) ||
        !hasExtension(extensions, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        return false;
    }
    VkPhysicalDeviceExternalImageFormatInfo external{};
    external.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    external.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkPhysicalDeviceImageFormatInfo2 query{};
    query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    query.pNext = &external;
    query.format = VK_FORMAT_R8G8B8A8_UNORM;
    query.type = VK_IMAGE_TYPE_2D;
    query.tiling = VK_IMAGE_TILING_OPTIMAL;
    query.usage = usage;

    VkExternalImageFormatProperties externalProps{};
    externalProps.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    VkImageFormatProperties2 props{};
    props.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    props.pNext = &externalProps;

    if (getImageFormat2(physical, &query, &props) != VK_SUCCESS) return false;
    const auto& mem = externalProps.externalMemoryProperties;
    return (mem.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0 &&
           (mem.compatibleHandleTypes & VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID) != 0 &&
           props.imageFormatProperties.maxExtent.width >= minWidth &&
           props.imageFormatProperties.maxExtent.height >= minHeight;
}

bool supportsSyncFdImport(const ScopedArray<VkExtensionProperties>& extensions,
                        PFN_vkGetPhysicalDeviceExternalSemaphoreProperties getSemaphoreProps,
                        VkPhysicalDevice physical) {
    if (!getSemaphoreProps ||
        !hasExtension(extensions, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
        return false;
    }
    VkPhysicalDeviceExternalSemaphoreInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
    info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

    VkExternalSemaphoreProperties props{};
    props.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
    getSemaphoreProps(physical, &info, &props);
    return (props.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0 &&
           (props.compatibleHandleTypes & VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT) != 0;
}

int scoreCandidate(jlong flags, VkPhysicalDeviceType type) {
    if ((flags & kFlagQcomBlockMatch) != 0) return 4;
    if ((flags & kFlagVulkanCompute) == 0) return 0;
    if (type == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return 3;
    if (type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) return 2;
    return 1;
}

jlong packCapabilities(jlong flags, uint32_t subgroupSize, uint32_t vendorId) {
    const uint64_t clampedSubgroup = std::min<uint32_t>(subgroupSize, 255u);
    const uint64_t packed = static_cast<uint64_t>(flags) |
                            (clampedSubgroup << 16) |
                            (static_cast<uint64_t>(vendorId) << 32);
    return static_cast<jlong>(packed);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameWindowGeometry_extent(
        JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    const int width = ANativeWindow_getWidth(window);
    const int height = ANativeWindow_getHeight(window);
    ANativeWindow_release(window);
    if (width <= 0 || height <= 0) return 0;
    return (static_cast<jlong>(width) << 32) | static_cast<unsigned>(height);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_Bilibili_1Innocent_1Lab_xposedmodule_hook_feature_interpolation_FrameWindowGeometry_probeGpuCapabilities(
        JNIEnv*, jobject) {
    PFN_vkGetInstanceProcAddr getProc = loadVkGetInstanceProcAddr();
    if (!getProc) return 0;

    auto createInstance = instanceProc<PFN_vkCreateInstance>(getProc, nullptr, "vkCreateInstance");
    if (!createInstance) return 0;

    uint32_t instanceApi = VK_API_VERSION_1_1;
    if (auto enumerateVersion = instanceProc<PFN_vkEnumerateInstanceVersion>(
            getProc, nullptr, "vkEnumerateInstanceVersion")) {
        uint32_t reported = 0;
        if (enumerateVersion(&reported) == VK_SUCCESS && reported >= VK_API_VERSION_1_0) {
            instanceApi = std::min(reported, static_cast<uint32_t>(VK_API_VERSION_1_3));
        }
    }

    VkInstance instance = VK_NULL_HANDLE;
    auto tryCreateInstance = [&](uint32_t apiVersion) -> VkResult {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "BILCapabilityProbe";
        app.apiVersion = apiVersion;

        VkInstanceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        createInfo.pApplicationInfo = &app;
        return createInstance(&createInfo, nullptr, &instance);
    };

    if (tryCreateInstance(instanceApi) != VK_SUCCESS) {
        instanceApi = VK_API_VERSION_1_0;
        if (tryCreateInstance(instanceApi) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
            return 0;
        }
    }

    auto destroyInstance = instanceProc<PFN_vkDestroyInstance>(getProc, instance, "vkDestroyInstance");
    auto enumeratePhysicalDevices = instanceProc<PFN_vkEnumeratePhysicalDevices>(
        getProc, instance, "vkEnumeratePhysicalDevices");
    auto enumerateDeviceExt = instanceProc<PFN_vkEnumerateDeviceExtensionProperties>(
        getProc, instance, "vkEnumerateDeviceExtensionProperties");
    auto getProps = instanceProc<PFN_vkGetPhysicalDeviceProperties>(
        getProc, instance, "vkGetPhysicalDeviceProperties");
    auto getQueues = instanceProc<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        getProc, instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    auto getImageFormat = instanceProc<PFN_vkGetPhysicalDeviceImageFormatProperties>(
        getProc, instance, "vkGetPhysicalDeviceImageFormatProperties");
    auto getProps2 = instanceProc<PFN_vkGetPhysicalDeviceProperties2>(
        getProc, instance, "vkGetPhysicalDeviceProperties2");
    auto getFeatures2 = instanceProc<PFN_vkGetPhysicalDeviceFeatures2>(
        getProc, instance, "vkGetPhysicalDeviceFeatures2");
    auto getFormatProps2 = instanceProc<PFN_vkGetPhysicalDeviceFormatProperties2>(
        getProc, instance, "vkGetPhysicalDeviceFormatProperties2");
    auto getImageFormat2 = instanceProc<PFN_vkGetPhysicalDeviceImageFormatProperties2>(
        getProc, instance, "vkGetPhysicalDeviceImageFormatProperties2");
    auto getSemaphoreProps = instanceProc<PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(
        getProc, instance, "vkGetPhysicalDeviceExternalSemaphoreProperties");

    jlong bestPacked = kFlagVulkanAvailable;
    int bestScore = 0;

    if (enumeratePhysicalDevices && enumerateDeviceExt && getProps && getQueues) {
        uint32_t deviceCount = 0;
        if (enumeratePhysicalDevices(instance, &deviceCount, nullptr) == VK_SUCCESS && deviceCount > 0) {
            deviceCount = std::min(deviceCount, 16u);
            ScopedArray<VkPhysicalDevice> devices(deviceCount);
            if (!devices.empty() &&
                enumeratePhysicalDevices(instance, &deviceCount, devices.data()) == VK_SUCCESS) {
                for (uint32_t d = 0; d < deviceCount; ++d) {
                    VkPhysicalDevice physical = devices[d];
                    jlong flags = kFlagVulkanAvailable;
                    uint32_t subgroupSize = 0;

                    VkPhysicalDeviceProperties baseProps{};
                    getProps(physical, &baseProps);

                    uint32_t qCount = 0;
                    getQueues(physical, &qCount, nullptr);
                    bool hasComputeQueue = false;
                    if (qCount > 0) {
                        qCount = std::min(qCount, 32u);
                        ScopedArray<VkQueueFamilyProperties> queues(qCount);
                        if (!queues.empty()) {
                            getQueues(physical, &qCount, queues.data());
                            for (uint32_t q = 0; q < qCount; ++q) {
                                if (queues[q].queueCount > 0 &&
                                    (queues[q].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                                    hasComputeQueue = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!hasComputeQueue) {
                        const int score = scoreCandidate(flags, baseProps.deviceType);
                        if (score > bestScore) {
                            bestScore = score;
                            bestPacked = packCapabilities(flags, subgroupSize, baseProps.vendorID);
                        }
                        continue;
                    }
                    flags |= kFlagVulkanCompute;

                    uint32_t extCount = 0;
                    if (enumerateDeviceExt(physical, nullptr, &extCount, nullptr) != VK_SUCCESS) {
                        extCount = 0;
                    }
                    extCount = std::min(extCount, 1024u);
                    ScopedArray<VkExtensionProperties> extensions(extCount);
                    if (extCount > 0 &&
                        (extensions.empty() ||
                         enumerateDeviceExt(physical, nullptr, &extCount, extensions.data()) != VK_SUCCESS)) {
                        extCount = 0;
                    }

                    const bool syncFdOk = supportsSyncFdImport(extensions, getSemaphoreProps, physical);
                    if (syncFdOk) flags |= kFlagVulkanSyncFd;

                    constexpr VkImageUsageFlags ahbUsage =
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                    const bool qcomAhbOk =
                        syncFdOk &&
                        supportsAhbImport(extensions, getImageFormat2, physical, ahbUsage,
                                          kQcomPackedLumaWidth, kQcomMotionHeight);
                    // MeguvizCompute AHB interop requires AHB external memory + foreign queue;
                    // syncFd is tracked separately because drivers can still run host-wait AHB without it.
                    const bool vulkanAhbOk = supportsAhbImport(
                        extensions, getImageFormat2, physical, VK_IMAGE_USAGE_SAMPLED_BIT, 1280, 720);
                    if (vulkanAhbOk) flags |= kFlagVulkanAhb;

                    // Query optional GPU architecture features matching MeguvizCompute/ncnn-meguviz.
                    if (instanceApi >= VK_API_VERSION_1_1 &&
                        baseProps.apiVersion >= VK_API_VERSION_1_1 &&
                        getProps2 && getFeatures2) {
                        VkPhysicalDeviceSubgroupProperties subgroupProps{};
                        subgroupProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
                        VkPhysicalDeviceProperties2 props2{};
                        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                        props2.pNext = &subgroupProps;
                        getProps2(physical, &props2);
                        subgroupSize = subgroupProps.subgroupSize;
                        constexpr VkSubgroupFeatureFlags usefulSubgroupOps =
                            VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT |
                            VK_SUBGROUP_FEATURE_SHUFFLE_BIT | VK_SUBGROUP_FEATURE_VOTE_BIT;
                        if (subgroupProps.subgroupSize >= 4 &&
                            (subgroupProps.supportedOperations & usefulSubgroupOps) != 0) {
                            flags |= kFlagSubgroupOps;
                        }

                        const bool queryFp16 =
                            baseProps.apiVersion >= VK_API_VERSION_1_2 ||
                            hasExtension(extensions, VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
                        const bool queryCoop =
                            hasExtension(extensions, VK_KHR_COOPERATIVE_MATRIX_EXTENSION_NAME);
                        const bool queryDot =
                            baseProps.apiVersion >= VK_API_VERSION_1_3 ||
                            hasExtension(extensions, VK_KHR_SHADER_INTEGER_DOT_PRODUCT_EXTENSION_NAME);
                        const bool queryQcom =
                            hasExtension(extensions, VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME);

                        VkPhysicalDeviceShaderFloat16Int8Features fp16Features{};
                        fp16Features.sType =
                            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
                        VkPhysicalDeviceCooperativeMatrixFeaturesKHR coopFeatures{};
                        coopFeatures.sType =
                            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_MATRIX_FEATURES_KHR;
                        VkPhysicalDeviceShaderIntegerDotProductFeatures dotFeatures{};
                        dotFeatures.sType =
                            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES;
                        VkPhysicalDeviceImageProcessingFeaturesQCOM qcomFeatures{};
                        qcomFeatures.sType =
                            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM;

                        VkPhysicalDeviceFeatures2 features2{};
                        features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                        void** nextPtr = &features2.pNext;
                        if (queryFp16) {
                            *nextPtr = &fp16Features;
                            nextPtr = &fp16Features.pNext;
                        }
                        if (queryCoop) {
                            *nextPtr = &coopFeatures;
                            nextPtr = &coopFeatures.pNext;
                        }
                        if (queryDot) {
                            *nextPtr = &dotFeatures;
                            nextPtr = &dotFeatures.pNext;
                        }
                        if (queryQcom) {
                            *nextPtr = &qcomFeatures;
                            nextPtr = &qcomFeatures.pNext;
                        }
                        getFeatures2(physical, &features2);

                        if (queryFp16 && fp16Features.shaderFloat16 == VK_TRUE) {
                            flags |= kFlagShaderFp16;
                        }
                        if (queryCoop && coopFeatures.cooperativeMatrix == VK_TRUE) {
                            flags |= kFlagCoopMatrix;
                        }
                        if (queryDot && dotFeatures.shaderIntegerDotProduct == VK_TRUE) {
                            flags |= kFlagDotProduct;
                        }

                        // Align QCOM block-match capability check with Meguviz qcom_motion.cpp:358-418.
                        if (queryQcom && qcomFeatures.textureBlockMatch == VK_TRUE && getImageFormat) {
                            VkPhysicalDeviceImageProcessingPropertiesQCOM qcomProps{};
                            qcomProps.sType =
                                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_PROPERTIES_QCOM;
                            VkPhysicalDeviceProperties2 qcomPropsQuery{};
                            qcomPropsQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
                            qcomPropsQuery.pNext = &qcomProps;
                            getProps2(physical, &qcomPropsQuery);

                            const bool tileOk =
                                qcomProps.maxBlockMatchRegion.width >= kQcomMotionTile &&
                                qcomProps.maxBlockMatchRegion.height >= kQcomMotionTile;
                            const bool coreSpirv14 =
                                instanceApi >= VK_API_VERSION_1_2 &&
                                baseProps.apiVersion >= VK_API_VERSION_1_2;
                            const bool spirv14Ok = coreSpirv14 ||
                                (hasExtension(extensions, VK_KHR_SPIRV_1_4_EXTENSION_NAME) &&
                                 hasExtension(extensions, VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME));

                            bool formatOk = false;
                            if (tileOk && spirv14Ok) {
                                const bool coreFlags2 =
                                    instanceApi >= VK_API_VERSION_1_3 &&
                                    baseProps.apiVersion >= VK_API_VERSION_1_3;
                                const bool extFlags2 =
                                    hasExtension(extensions, VK_KHR_FORMAT_FEATURE_FLAGS_2_EXTENSION_NAME);
                                constexpr VkImageUsageFlags blockUsage =
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                    VK_IMAGE_USAGE_SAMPLED_BIT |
                                    VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM;
                                const VkFormat candidates[2] = {
                                    VK_FORMAT_R8_UNORM,
                                    VK_FORMAT_R8G8B8A8_UNORM,
                                };
                                for (VkFormat fmt : candidates) {
                                    if (coreFlags2 || extFlags2) {
                                        if (!getFormatProps2) continue;
                                        VkFormatProperties3 fmtProps3{};
                                        fmtProps3.sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_3;
                                        VkFormatProperties2 fmtProps2{};
                                        fmtProps2.sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2;
                                        fmtProps2.pNext = &fmtProps3;
                                        getFormatProps2(physical, fmt, &fmtProps2);
                                        if ((fmtProps3.optimalTilingFeatures &
                                             VK_FORMAT_FEATURE_2_BLOCK_MATCHING_BIT_QCOM) == 0) {
                                            continue;
                                        }
                                    }
                                    VkImageFormatProperties limits{};
                                    if (getImageFormat(physical, fmt, VK_IMAGE_TYPE_2D,
                                                       VK_IMAGE_TILING_OPTIMAL, blockUsage, 0,
                                                       &limits) == VK_SUCCESS &&
                                        limits.maxExtent.width >= kQcomMotionWidth &&
                                        limits.maxExtent.height >= kQcomMotionHeight) {
                                        formatOk = true;
                                        break;
                                    }
                                }
                            }
                            if (formatOk) {
                                flags |= kFlagQcomBlockMatch;
                                if (qcomAhbOk) flags |= kFlagQcomAhb;
                            }
                        }
                    }

                    const int score = scoreCandidate(flags, baseProps.deviceType);
                    if (score > bestScore) {
                        bestScore = score;
                        bestPacked = packCapabilities(flags, subgroupSize, baseProps.vendorID);
                    }
                }
            }
        }
    }

    if (destroyInstance) destroyInstance(instance, nullptr);
    return bestPacked;
}
