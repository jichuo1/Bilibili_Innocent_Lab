#!/system/bin/sh
# Poll the 128-frame SF ring often enough to retain every actual presentation.
set -eu
out=${1:?output path}
count=${2:-900}
layer=
attempt=0
while [ -z "$layer" ] && [ "$attempt" -lt 30 ]; do
    layer=$(dumpsys SurfaceFlinger --list | sed -n 's/^RequestedLayerState{\(com.Bilibili_Innocent_Lab.xposedmodule\/com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity#[0-9]*\) parentId=.*/\1/p' | sort -n -t '#' -k2,2 | tail -n 1)
    attempt=$((attempt + 1))
    [ -n "$layer" ] || sleep .5
done
[ -n "$layer" ] || { echo 'Active module buffer layer not found' >&2; exit 1; }
echo "# layer=$layer" > "$out"
i=0
while [ "$i" -lt "$count" ]; do
    dumpsys SurfaceFlinger --latency "$layer" >> "$out"
    sleep .5
    i=$((i + 1))
done
echo "$out"
