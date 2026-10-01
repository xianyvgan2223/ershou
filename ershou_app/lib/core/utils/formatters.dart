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

  /// 面交时间窗：把后端的 ISO 串（`2026-10-02T12:00:00`）显示成人看的样子。
  ///
  /// 以前直接把 ISO 串拼出来，界面上是 `2026-10-02T12:00:00 ~ 2026-10-02T13:00:00`，
  /// 那个 `T` 和重复的日期很扎眼。规则：
  /// - 同一天：`10-02 12:00 ~ 13:00`
  /// - 跨天：`10-02 12:00 ~ 10-03 13:00`
  static String slot(String? start, String? end) {
    final s = _parse(start);
    if (s == null) {
      // 解析不出来就退回原文，至少别显示空白
      final raw = (start ?? '').trim();
      if (raw.isEmpty) return '待协商';
      final e = (end ?? '').trim();
      return e.isEmpty ? raw : raw + ' ~ ' + e;
    }
    final e = _parse(end);
    final sText = '${_two(s.month)}-${_two(s.day)} ${_two(s.hour)}:${_two(s.minute)}';
    if (e == null) return sText;
    final sameDay = s.year == e.year && s.month == e.month && s.day == e.day;
    final eText = sameDay
        ? '${_two(e.hour)}:${_two(e.minute)}'
        : '${_two(e.month)}-${_two(e.day)} ${_two(e.hour)}:${_two(e.minute)}';
    return '$sText ~ $eText';
  }

  /// 兼容 `2026-10-02T12:00:00` 与 `2026-10-02 12:00:00` 两种写法
  static DateTime? _parse(String? raw) {
    final v = (raw ?? '').trim();
    if (v.isEmpty) return null;
    return DateTime.tryParse(v) ?? DateTime.tryParse(v.replaceFirst(' ', 'T'));
  }

  static String _two(int v) => v < 10 ? '0' + v.toString() : v.toString();
}
