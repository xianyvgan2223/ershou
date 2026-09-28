/// 时间 / 价格 / 浏览量的展示格式。
class Fmt {
  const Fmt._();

  /// 后端返回的是 "2026-09-26T14:23:55"，直接贴到界面上很丑。
  static String timeAgo(String? raw) {
    if (raw == null || raw.isEmpty) return '';
    final date = DateTime.tryParse(raw);
    if (date == null) return raw;
    final now = DateTime.now();
    final diff = now.difference(date);
    if (diff.inSeconds < 60) return '刚刚';
    if (diff.inMinutes < 60) return diff.inMinutes.toString() + ' 分钟前';
    if (diff.inHours < 24) return diff.inHours.toString() + ' 小时前';
    if (diff.inDays < 30) return diff.inDays.toString() + ' 天前';
    final sameYear = date.year == now.year;
    final md = _two(date.month) + '-' + _two(date.day);
    return sameYear ? md : date.year.toString() + '-' + md;
  }

  /// 列表里价格只保留必要小数：99 / 99.5 / 99.55
  static String price(double value) {
    if (value == value.roundToDouble()) return value.toStringAsFixed(0);
    if ((value * 10) == (value * 10).roundToDouble()) return value.toStringAsFixed(1);
    return value.toStringAsFixed(2);
  }

  static String count(int value) {
    if (value < 1000) return value.toString();
    if (value < 10000) return (value / 1000).toStringAsFixed(1) + 'k';
    return (value / 10000).toStringAsFixed(1) + 'w';
  }

  /// 手机号脱敏：138****0001（后端已经脱敏，这里兜底）
  static String phone(String? raw) {
    if (raw == null || raw.isEmpty) return '';
    if (raw.contains('*')) return raw;
    if (raw.length != 11) return raw;
    return raw.substring(0, 3) + '****' + raw.substring(7);
  }

  static String _two(int v) => v < 10 ? '0' + v.toString() : v.toString();
}
