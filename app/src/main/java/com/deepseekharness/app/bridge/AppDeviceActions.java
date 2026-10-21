package com.deepseekharness.app.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.util.BoundedUiCall;
import com.deepseekharness.app.util.SensitiveData;

/** 应用启动和剪贴板动作；授权由当前桥传入，不持有第二套运行或授权状态。 */
public final class AppDeviceActions {
  private AppDeviceActions() {}

  /** /app/launch?pkg=包名 ：启动应用（App 层，不需要 ADB） */
  public static String launch(
      Context ctx,
      String pkg,
      java.util.function.BiFunction<String, String, String> confirmationFailure,
      java.util.function.BooleanSupplier current) {
    boolean submitted = false;
    try {
      if (pkg.isEmpty()) return "NO_PKG";
      if (!current.getAsBoolean()) return "[ERR] SCREEN_RUN_CHANGED";
      String failure =
          confirmationFailure.apply(
              com.deepseekharness.app.util.UiText.format("启动应用：%s", pkg), pkg);
      if (failure != null) return "[ERR] " + failure;
      android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
      if (i == null)
        return com.deepseekharness.app.util.UiText.format("NOT_FOUND: %s（该应用没有启动入口或未安装）", pkg);
      i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      if (!current.getAsBoolean()) return "[ERR] SCREEN_RUN_CHANGED";
      submitted = true;
      ctx.startActivity(i);
      return com.deepseekharness.app.util.UiText.format("OK: 已启动 %s", pkg);
    } catch (Throwable e) {
      return (submitted ? "[RESULT_UNKNOWN] APP_LAUNCH_RESULT_UNKNOWN: " : "ERROR: ")
          + safeError(e);
    }
  }

  /**
   * /app/install?path=/sdcard/Download/DSHA/x.apk ：用已授权的 root 通道静默安装 APK。
   *
   * <p>不弹系统安装器：{@code pm install} 由特权 uid 执行，成功后应用立即可用。
   * 因此 root 开关是硬前提（设置 → 设备能力授权 → Root）；通用设备命令仍不开放
   * {@code pm install}，安装只走 root 通道里这条固定形状的受管调用。
   */
  public static String install(Context ctx, String path) {
    try {
      if (path.isEmpty()) return "NO_PATH";
      if (!com.deepseekharness.app.util.BridgePathPolicy.isAbsolute(path))
        return com.deepseekharness.app.util.UiText.text(
            "FORBIDDEN: 安装需要绝对路径（可先下载到 Download/DSHA）");
      if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
        return com.deepseekharness.app.util.UiText.text("BAD_APK: 只接受 .apk 文件");
      String canonical;
      try {
        canonical = new java.io.File(path).getCanonicalPath();
      } catch (Throwable unreadable) {
        return com.deepseekharness.app.util.UiText.format("FORBIDDEN: 路径无法解析（%s）", path);
      }
      if (com.deepseekharness.app.util.BridgePathPolicy.denied(canonical))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      return com.deepseekharness.app.RootShell.install(ctx, canonical);
    } catch (Throwable e) {
      return "ERROR: " + safeError(e);
    }
  }

  /** /app/clip 读剪贴板；/app/clip?text=xxx 写剪贴板 */
  public static String clipboard(Context ctx, Handler mainHandler, String text) {
    try {
      // HTTP 工作线程有界等待。不能在排队后先报成功，也不能因等待超时就假定尚未写入。
      if (Looper.myLooper() == Looper.getMainLooper())
        return com.deepseekharness.app.util.UiText.text("ERROR: 剪贴板桥需由请求工作线程调用");
      BoundedUiCall.Result<String> result =
          BoundedUiCall.call(
              new BoundedUiCall.Dispatcher() {
                @Override
                public boolean post(Runnable task) {
                  return mainHandler.post(task);
                }

                @Override
                public void remove(Runnable task) {
                  mainHandler.removeCallbacks(task);
                }
              },
              () -> {
                android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return "NO_SERVICE";
                if (!text.isEmpty()) {
                  cm.setPrimaryClip(
                      com.deepseekharness.app.bridge.SensitiveClipboard.text("DSHA", text));
                  return com.deepseekharness.app.util.UiText.format(
                      "OK: 已写入剪贴板（%s 字）", text.length());
                }
                // 读取时复核真正前台窗口，WebView/Gecko 同样属于前台应用。
                if (!ForegroundActivity.isResumed(ForegroundActivity.current()))
                  return com.deepseekharness.app.util.UiText.text(
                      "[APP_BACKGROUND] 系统限制：只有 App 在前台时才能读剪贴板，可先用 /app/notify 提醒用户打开 DSHA");
                android.content.ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0)
                  return com.deepseekharness.app.util.UiText.text("（剪贴板为空）");
                CharSequence cs = cd.getItemAt(0).coerceToText(ctx);
                String value = cs == null ? "" : cs.toString();
                return value.length() > 8192
                    ? com.deepseekharness.app.util.UiText.format(
                        "%s…（已截断）", value.substring(0, 8192))
                    : value;
              },
              3000);
      switch (result.status) {
        case SUCCESS:
          return result.value;
        case FAILED:
          return com.deepseekharness.app.util.UiText.format(
              "ERROR: 剪贴板操作失败：%s", safeError(result.error));
        case NOT_EXECUTED:
          return com.deepseekharness.app.util.UiText.format(
              "%s主线程尚未执行，已取消本次剪贴板操作；可重试", (result.interrupted ? "[INTERRUPTED] " : "[TIMEOUT] "));
        default:
          return com.deepseekharness.app.util.UiText.text(
              "[RESULT_UNKNOWN] 剪贴板操作已开始，但等待已结束，结果未知；请确认后再重试");
      }
    } catch (Throwable e) {
      return "ERROR: " + safeError(e);
    }
  }

  private static String safeError(Throwable error) {
    return SensitiveData.redact(String.valueOf(error));
  }
}
