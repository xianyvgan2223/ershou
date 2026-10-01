/// 坐标系工具（与 Web 端 `ershou-web/src/utils/coord.ts` 同一套公式，务必保持一致）。
///
/// 本项目涉及三套坐标：
/// - **GCJ-02**：国测局坐标，高德地图用，也是 `trade_point.lng/lat` 入库的坐标；
/// - **WGS-84**：真实 GPS，手机 `geolocator` 拿到的就是这个；
/// - **像素坐标**：`map_type=1` 的自绘底图校区用（`coord_x/coord_y`）。
///
/// 国内 GCJ-02 与 WGS-84 相差 **几百米**（桂林实测 475~567 米，不是常说的"50~200 米"），
/// 所以"手机定位 → 附近点位检索"这条路上必须先转换，否则会偏出好几栋楼。
///
/// ⚠️ Web 端优先用高德的 `AMap.convertFrom`（权威数据），本公式只作兜底。
/// 移动端目前只有这个公式——如果发现偏差明显，应该改成在 WebView 里调一次
/// `AMap.convertFrom` 再把结果回传（JS 通道已经通了，加这个不难）。
library;

import 'dart:math' as math;

const double _chinaLngMin = 72.004;
const double _chinaLngMax = 137.8347;
const double _chinaLatMin = 0.8293;
const double _chinaLatMax = 55.8271;

/// 椭球参数（克拉索夫斯基）
const double _a = 6378245.0;
const double _ee = 0.00669342162296594323;
const double _pi = math.pi;

bool isOutOfChina(double lng, double lat) =>
    lng < _chinaLngMin || lng > _chinaLngMax || lat < _chinaLatMin || lat > _chinaLatMax;

double _transformLat(double x, double y) {
  // 注意首项是 **+100.0**：写成 -100.0 会让偏移量翻倍、方向还反（Web 端踩过这个坑）
  var ret = 100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(x.abs());
  ret += ((20.0 * math.sin(6.0 * x * _pi) + 20.0 * math.sin(2.0 * x * _pi)) * 2.0) / 3.0;
  ret += ((20.0 * math.sin(y * _pi) + 40.0 * math.sin((y / 3.0) * _pi)) * 2.0) / 3.0;
  ret += ((160.0 * math.sin((y / 12.0) * _pi) + 320 * math.sin((y * _pi) / 30.0)) * 2.0) / 3.0;
  return ret;
}

double _transformLng(double x, double y) {
  var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(x.abs());
  ret += ((20.0 * math.sin(6.0 * x * _pi) + 20.0 * math.sin(2.0 * x * _pi)) * 2.0) / 3.0;
  ret += ((20.0 * math.sin(x * _pi) + 40.0 * math.sin((x / 3.0) * _pi)) * 2.0) / 3.0;
  ret += ((150.0 * math.sin((x / 12.0) * _pi) + 300.0 * math.sin((x / 30.0) * _pi)) * 2.0) / 3.0;
  return ret;
}

/// WGS-84（手机 GPS）→ GCJ-02（高德 / 本项目入库坐标）。
/// 纯本地计算，不调接口、不消耗高德配额；境外坐标原样返回。
List<double> wgs84ToGcj02(double lng, double lat) {
  if (isOutOfChina(lng, lat)) return [lng, lat];
  var dLat = _transformLat(lng - 105.0, lat - 35.0);
  var dLng = _transformLng(lng - 105.0, lat - 35.0);
  final radLat = (lat / 180.0) * _pi;
  var magic = math.sin(radLat);
  magic = 1 - _ee * magic * magic;
  final sqrtMagic = math.sqrt(magic);
  dLat = (dLat * 180.0) / (((_a * (1 - _ee)) / (magic * sqrtMagic)) * _pi);
  dLng = (dLng * 180.0) / ((_a / sqrtMagic) * math.cos(radLat) * _pi);
  return [lng + dLng, lat + dLat];
}

/// GCJ-02 → WGS-84（数值迭代反算，3 次足够到亚米级）
List<double> gcj02ToWgs84(double lng, double lat) {
  if (isOutOfChina(lng, lat)) return [lng, lat];
  var wLng = lng;
  var wLat = lat;
  for (var i = 0; i < 3; i++) {
    final g = wgs84ToGcj02(wLng, wLat);
    wLng += lng - g[0];
    wLat += lat - g[1];
  }
  return [wLng, wLat];
}
