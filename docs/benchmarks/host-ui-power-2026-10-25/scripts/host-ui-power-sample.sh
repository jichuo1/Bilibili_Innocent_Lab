#!/system/bin/sh
# Run as root on the explicitly selected test device while the opt-in benchmark runs.
# /proc/uptime and Android elapsedRealtime share the same suspend-inclusive clock.
set -eu
out=${1:?output TSV path}
count=${2:-1600}
read suspended < /sys/class/power_supply/battery/input_suspend
read status < /sys/class/power_supply/battery/status
[ "$suspended" = 1 ] && [ "$status" = Discharging ] || { echo 'Real discharge required' >&2; exit 1; }
echo 'elapsed_s current_uA voltage_uV charge_uAh temp_deciC input_suspend status gpu_busy_us gpu_total_us gpu_hz' > "$out"
i=0
while [ "$i" -lt "$count" ]; do
    read elapsed idle < /proc/uptime
    read current < /sys/class/power_supply/battery/current_now
    read voltage < /sys/class/power_supply/battery/voltage_now
    read charge < /sys/class/power_supply/battery/charge_counter
    read temp < /sys/class/power_supply/battery/temp
    read suspended < /sys/class/power_supply/battery/input_suspend
    read status < /sys/class/power_supply/battery/status
    read busy total < /sys/class/kgsl/kgsl-3d0/gpubusy
    read hz < /sys/class/kgsl/kgsl-3d0/gpuclk
    echo "$elapsed $current $voltage $charge $temp $suspended $status $busy $total $hz" >> "$out"
    i=$((i + 1))
    sleep .5
done
echo "$out"
