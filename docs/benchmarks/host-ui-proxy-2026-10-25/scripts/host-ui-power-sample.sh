#!/system/bin/sh
# Mi 10: dc covers the wireless input; usb/pc_port cover wired charging.
set -eu
out=${1:?output TSV path}
count=${2:-900}
echo 'elapsed_s current_uA voltage_uV charge_uAh temp_deciC input_suspend status gpu_busy_us gpu_total_us gpu_hz external_online' > "$out"
i=0
while [ "$i" -lt "$count" ]; do
    read elapsed idle < /proc/uptime
    read current < /sys/class/power_supply/battery/current_now
    read voltage < /sys/class/power_supply/battery/voltage_now
    read charge < /sys/class/power_supply/battery/charge_counter
    read temp < /sys/class/power_supply/battery/temp
    read suspended < /sys/class/power_supply/battery/input_suspend
    read status < /sys/class/power_supply/battery/status
    external=0
    for supply in usb dc pc_port; do
        if [ -r "/sys/class/power_supply/$supply/online" ]; then
            read online < "/sys/class/power_supply/$supply/online"
            if [ "$online" != 0 ]; then external=1; fi
        fi
    done
    [ "$status" = Discharging ] && { [ "$suspended" = 1 ] || [ "$external" = 0 ]; } || { echo 'Real discharge required' >&2; exit 1; }
    read busy total < /sys/class/kgsl/kgsl-3d0/gpubusy
    read hz < /sys/class/kgsl/kgsl-3d0/gpuclk
    echo "$elapsed $current $voltage $charge $temp $suspended $status $busy $total $hz $external" >> "$out"
    i=$((i + 1))
    sleep .5
done
echo "$out"
