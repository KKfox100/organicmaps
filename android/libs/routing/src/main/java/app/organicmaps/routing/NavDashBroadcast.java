package app.organicmaps.routing;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.Distance;

/**
 * 把导航数据推给外接仪表（ESP32 骑行仪表）。
 *
 * ============================================================
 *  为什么用广播，而不是在这里直接连 BLE
 * ============================================================
 *  在 Organic Maps 里直接实现 GATT 客户端要几百行：扫描、连接、MTU 协商、
 *  分片写、断线重连、Android 12+ 的 BLUETOOTH_CONNECT 运行时权限……
 *  而这些逻辑在配套的仪表 App 里已经跑了几周、踩完了坑。
 *
 *  所以这里只做一件事：把 RoutingInfo **原样**通过显式广播发出去，
 *  由仪表 App 接住后走它既有的 BLE 链路。好处：
 *
 *    · OM 这边只加一个文件 + 一行调用，改动面最小，上游升级好合并
 *    · 不用在 OM 里申请蓝牙权限，用户少一道授权
 *    · 依然是**零读屏** —— 这是进程间通信，不是去读界面上画了什么
 *
 * ============================================================
 *  为什么必须是显式广播（setPackage）
 * ============================================================
 *  Android 8.0 起对隐式广播做了限制，不带包名的自定义广播基本收不到。
 *  setPackage() 指定目标包名后就是显式广播，不受限制。
 */
public final class NavDashBroadcast
{
  /** 仪表 App 的包名。换 App 时只改这里。 */
  private static final String TARGET_PKG = "com.rider.navdash";

  public static final String ACTION = "com.rider.navdash.OM_NAV";

  // ---- extras 的键名。和仪表 App 侧的常量必须一致 ----
  public static final String K_TURN = "turn";
  public static final String K_DIST_M = "distM";
  public static final String K_REMAIN_M = "remainM";
  public static final String K_ETA_S = "etaS";
  public static final String K_ROAD = "road";
  public static final String K_NEXT_ROAD = "nextRoad";
  public static final String K_SPEED_LIMIT = "speedLimitKmh";

  private NavDashBroadcast() {}

  /**
   * 推一次导航状态。
   *
   * 调用频率就是 [NavigationService] 的刷新频率（约 1Hz），
   * 由 OM 自己的节流控制 —— 不需要我们再限流。
   */
  public static void push(@NonNull Context ctx, @Nullable RoutingInfo info)
  {
    if (info == null)
      return;

    final Intent i = new Intent(ACTION);
    i.setPackage(TARGET_PKG);

    // 转向方向直接给枚举名。仪表侧做一次映射就能用，
    // 而且比传 int 序号可读 —— 上游万一调整枚举顺序也不会错位。
    i.putExtra(K_TURN, info.carDirection == null ? "NoTurn" : info.carDirection.name());

    i.putExtra(K_DIST_M, toMeters(info.distToTurn));   // 到下一个转向
    i.putExtra(K_REMAIN_M, toMeters(info.distToTarget)); // 到终点
    i.putExtra(K_ETA_S, info.totalTimeInSeconds);
    i.putExtra(K_ROAD, nullToEmpty(info.currentStreet));
    i.putExtra(K_NEXT_ROAD, nullToEmpty(info.nextStreet));

    // 限速：OM 给的是 m/s，转成 km/h。0 表示这条路没有限速数据。
    i.putExtra(K_SPEED_LIMIT, (int) Math.round(info.speedLimitMps * 3.6));

    ctx.sendBroadcast(i);
  }

  /**
   * Distance 是一个「数值 + 单位」的复合类型，直接取 mDistance 会因为单位
   * 不同（米/公里/英尺/英里）差好几个数量级。这里统一折算成米。
   */
  private static int toMeters(@Nullable Distance d)
  {
    if (d == null || !d.isValid())
      return -1;

    double v = d.mDistance;
    if (d.mUnits == Distance.Units.Kilometers || d.mUnits == Distance.Units.Miles)
      v *= 1000;
    else if (d.mUnits == Distance.Units.Feet)
      v /= 3.28084;

    return (int) Math.round(v);
  }

  @NonNull
  private static String nullToEmpty(@Nullable String s)
  {
    return s == null ? "" : s;
  }
}
