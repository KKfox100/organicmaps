package app.organicmaps.sdk.sound;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
/**
 * 把 OM 要念的那句话，原样交给「骑行助手」App，由车上的 ESP32 喇叭播出来。
 *
 * ============================================================
 *  为什么直接转发**文本**，而不是转发音频
 * ============================================================
 *  · 文本只有几十字节，走 BLE 一点压力都没有；音频要 16KB/s，
 *    还要处理断流、缓冲、编码，复杂一个数量级。
 *  · 文本是 OM 自己拼好的整句（含路名，如「在两百米后，左转进入龙岗大道」），
 *    转发后**内容与原播报完全一致**，不需要在任何地方重新实现文案模板。
 *
 * ============================================================
 *  手机要不要也出声：交给设置里的开关
 * ============================================================
 *  设置 → 语音指令 → 「静音手机语音（改由车上的喇叭播）」
 *   · 开：只转给车上的喇叭，手机静音（骑车时用这个）
 *   · 关：两边都响（默认，便于确认链路通没通）
 *
 *  没有做成「检测到 App 就自动静音」：那样用户装完 App 会发现手机突然不说话了，
 *  却不知道是哪里关的。显式开关更好排查。
 */
public final class NavDashSay
{
  /** 必须和 App 侧 OmNavReceiver.ACTION_SAY 保持一致 */
  private static final String ACTION_SAY = "com.rider.navdash.OM_SAY";
  private static final String TARGET_PKG = "com.rider.navdash";
  private static final String PREF_MUTE = "navdash_mute_local";

  private NavDashSay() {}

  /**
   * @return true = 手机不要外放（已经转交给车上设备）
   */
  public static boolean push(Context context, String text)
  {
    if (context == null || text == null || text.isEmpty())
      return false;

    // 【不要用 getPackageInfo 做「装没装」的前置判断】
    // Android 11+ 的包可见性会让它抛 NameNotFoundException —— 哪怕 App 就装着。
    // 实测：红米 K70 上 OM 本机能念，但广播一条都没发出去，就是栽在这里。
    // 广播发给不存在的包是安全的空操作，所以直接发，不做预检。
    // （manifest 里仍然声明了 <queries><package .../>，供界面上「是否已安装」用）

    try
    {
      final Intent i = new Intent(ACTION_SAY);
      i.setPackage(TARGET_PKG);
      i.putExtra("text", text);
      context.sendBroadcast(i);
    }
    catch (Exception e)
    {
      // 广播失败就照旧本机播：宁可重复，也不能没有声音
      return false;
    }

    // 直接用默认 SharedPreferences，避免 sdk 模块引入 androidx.preference 依赖。
    // 默认文件名就是 "<包名>_preferences"，和设置页用的是同一份。
    final SharedPreferences sp =
        context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    return sp.getBoolean(PREF_MUTE, false);
  }
}
