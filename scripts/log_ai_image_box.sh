#!/system/bin/sh
set -u

APP_PACKAGE="${2:-${APP_PACKAGE:-com.aiimagebox}}"
DURATION="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR="/data/data/com.termux/files/home/devwork/ai_image_box/scripts"
LOG_DIR="${LOG_DIR:-$SCRIPT_DIR}"

mkdir -p "$LOG_DIR" 2>/dev/null || {
  echo "无法创建日志目录: $LOG_DIR"
  exit 1
}

stamp="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/logcat_ai_image_box_${APP_PACKAGE}_${stamp}.log"
APP_PID="$(pidof "$APP_PACKAGE" 2>/dev/null | awk '{print $1}')"
LOGCAT_PID=""
DURATION_PID=""
ENDED=0

cleanup() {
  [ "$ENDED" -eq 1 ] && return
  ENDED=1
  echo ""
  echo "正在结束记录..."
  [ -n "${DURATION_PID:-}" ] && kill "$DURATION_PID" 2>/dev/null
  [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null
  wait "$LOGCAT_PID" 2>/dev/null
  echo "日志已保存: $LOG_FILE"
  exit 0
}

trap cleanup INT TERM

{
  echo "========== AI Image Box log capture =========="
  echo "start: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "package: $APP_PACKAGE"
  echo "pid: ${APP_PID:-<not running>}"
  echo "duration: ${DURATION:-until Ctrl+C}"
  echo "file: $LOG_FILE"
  echo "----------------------------------------"
} > "$LOG_FILE"

echo "开始记录 -> $LOG_FILE"

if [ -n "$APP_PID" ]; then
  logcat --pid="$APP_PID" -v threadtime >> "$LOG_FILE" 2>&1 &
  LOGCAT_PID=$!
else
  logcat -v threadtime 2>&1 | grep -iE "$APP_PACKAGE|AIImageBox|aiimagebox|Generation|Provider|okhttp" >> "$LOG_FILE" &
  LOGCAT_PID=$!
fi

if [ -n "$DURATION" ] && [ "$DURATION" -gt 0 ] 2>/dev/null; then
  (
    sleep "$DURATION"
    kill -TERM $$ 2>/dev/null
  ) &
  DURATION_PID=$!
fi

wait "$LOGCAT_PID" 2>/dev/null
cleanup

