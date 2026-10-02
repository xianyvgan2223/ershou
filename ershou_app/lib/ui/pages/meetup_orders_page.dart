import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'point_picker_page.dart';

/// 我的面交：买 / 卖双 tab，按角色给出可执行动作。
class MeetupOrdersPage extends ConsumerStatefulWidget {
  const MeetupOrdersPage({super.key});

  @override
  ConsumerState<MeetupOrdersPage> createState() => _MeetupOrdersPageState();
}

class _MeetupOrdersPageState extends ConsumerState<MeetupOrdersPage> {
  List<MeetupOrder> _orders = const [];
  String _role = 'buyer';
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await ref.read(orderRepositoryProvider).page(role: _role);
      if (!mounted) return;
      setState(() {
        _orders = page.records;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _act(Future<void> Function() action, String successText) async {
    try {
      await action();
      if (!mounted) return;
      showToast(context, successText);
      await _load();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (e) {
      if (mounted) showToast(context, '操作失败：' + e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final isBuyer = _role == 'buyer';
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Text('我的面交'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(44),
          child: Container(
            color: Colors.white,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Row(
              children: [
                _tab('我买的', 'buyer'),
                const SizedBox(width: 10),
                _tab('我卖的', 'seller'),
                const Spacer(),
                IconButton(
                  onPressed: _load,
                  icon: const Icon(Icons.refresh, size: 20),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _orders.isEmpty
              ? EmptyState(
                  icon: Icons.handshake_outlined,
                  message: _error ??
                      (isBuyer ? '还没有约过面交\n在商品详情页点「约面交」' : '还没有人约你的东西'),
                  onRetry: _load,
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    itemCount: _orders.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 6),
                    itemBuilder: (_, i) => _card(_orders[i], isBuyer),
                  ),
                ),
    );
  }

  Widget _tab(String label, String role) {
    final selected = _role == role;
    return Material(
      color: selected ? AppColors.brand : AppColors.bg,
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: () {
          if (_role == role) return;
          setState(() => _role = role);
          _load();
        },
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 13,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              color: selected ? Colors.white : AppColors.inkSoft,
            ),
          ),
        ),
      ),
    );
  }

  Widget _card(MeetupOrder o, bool isBuyer) {
    // 统一走 Fmt.slot：后端给的是 ISO 串，直接拼出来是 2026-10-02T12:00:00 很扎眼
    final timeText = o.timeSlotStart.isEmpty ? '时间待协商' : Fmt.slot(o.timeSlotStart, o.timeSlotEnd);
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: AppSpacing.page),
      child: AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                NetImage(url: o.itemCover, width: 64, height: 64, radius: 10),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        o.itemTitle,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                          height: 1.35,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          PriceText(value: Fmt.price(o.dealPrice), size: 16),
                          const SizedBox(width: 8),
                          TagChip(
                            text: o.statusText.isEmpty ? '状态未知' : o.statusText,
                            color: AppColors.brandDark,
                            background: AppColors.brandSoft,
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            _kv(Icons.place_outlined,
                o.pointName.isEmpty ? '面交点待协商' : o.pointName),
            const SizedBox(height: 4),
            _kv(Icons.schedule, timeText),
            const SizedBox(height: 4),
            _kv(Icons.person_outline,
                (isBuyer ? '卖家：' : '买家：') +
                    (isBuyer ? o.sellerName : o.buyerName)),
            if (o.remark.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                '备注：' + o.remark,
                style: const TextStyle(fontSize: 12, color: AppColors.inkSoft),
              ),
            ],
            // 终态单（已取消 4 / 已失效 5）把原因摊开说清楚。
            // 商品被卖给别人、被下架导致自动作废时，以前这里只有一个「已失效」。
            if (o.status == 4 || o.status == 5) ...[
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
                decoration: BoxDecoration(
                  color: const Color(0xFFFEF3F2),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  o.cancelReason.isEmpty ? '本次约面交已结束' : o.cancelReason,
                  style: const TextStyle(
                    fontSize: 12,
                    height: 1.4,
                    color: Color(0xFFB42318),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 12),
            _actions(o, isBuyer),
          ],
        ),
      ),
    );
  }

  Widget _kv(IconData icon, String text) => Row(
        children: [
          Icon(icon, size: 14, color: AppColors.inkFaint),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft),
            ),
          ),
        ],
      );

  /// 卖家改面交地点（顺带可定时间）。
  ///
  /// 流程：选点位（可点气泡选现成的，也可点空白新建）→ 可选时间 → PATCH。
  /// 改完后端会给买家发一条私聊提醒，让他在聊天里就能看到新地点。
  Future<void> _changeMeetup(MeetupOrder o) async {
    final point = await Navigator.of(context).push<MapPoint>(
      MaterialPageRoute<MapPoint>(
        builder: (_) => const PointPickerPage(pickExisting: true, title: '定面交地点'),
      ),
    );
    if (!mounted || point == null) return;
    // 时间窗选填：不填就是"待协商"，与发布/约面交时的口径一致
    final slot = await _askTimeSlot(o);
    if (!mounted || slot == null) return;
    try {
      await ref.read(orderRepositoryProvider).updatePoint(
            o.id,
            point.id,
            timeSlotStart: slot.$1,
            timeSlotEnd: slot.$2,
          );
      if (!mounted) return;
      showToast(context, '已把面交点改成「${point.name}」，买家会收到提醒');
      await _load();
    } catch (e) {
      if (mounted) showToast(context, '改面交地点失败：$e');
    }
  }

  /// 选时间窗：给几个常用档 + 保留原时间 + 待协商。
  /// 返回 (start, end)，格式与后端 FMT 一致：yyyy-MM-dd HH:mm:ss。
  Future<(String?, String?)?> _askTimeSlot(MeetupOrder o) async {
    String two(int v) => v.toString().padLeft(2, '0');
    String fmt(DateTime t) =>
        '${t.year}-${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}:00';

    final now = DateTime.now();
    // 今天/明天各给两个常见时段（课间与晚饭后）
    final options = <String, (String, String)>{};
    for (final day in [now, now.add(const Duration(days: 1))]) {
      final label = day.day == now.day ? '今天' : '明天';
      options['$label 12:00 - 13:00'] =
          (fmt(DateTime(day.year, day.month, day.day, 12)), fmt(DateTime(day.year, day.month, day.day, 13)));
      options['$label 18:00 - 19:00'] =
          (fmt(DateTime(day.year, day.month, day.day, 18)), fmt(DateTime(day.year, day.month, day.day, 19)));
    }
    final keepCurrent = o.timeSlotStart.isNotEmpty && o.timeSlotEnd.isNotEmpty;

    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 14, 16, 6),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text('面交时间（选填）',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
            if (keepCurrent)
              ListTile(
                dense: true,
                leading: const Icon(Icons.schedule, size: 18),
                title: Text('保持原时间：${Fmt.slot(o.timeSlotStart, o.timeSlotEnd)}',
                    style: const TextStyle(fontSize: 13.5)),
                onTap: () => Navigator.pop(ctx, '__keep__'),
              ),
            ...options.entries.map((e) => ListTile(
                  dense: true,
                  leading: const Icon(Icons.schedule, size: 18),
                  title: Text(e.key, style: const TextStyle(fontSize: 13.5)),
                  onTap: () => Navigator.pop(ctx, e.key),
                )),
            ListTile(
              dense: true,
              leading: const Icon(Icons.more_horiz, size: 18),
              title: const Text('待协商（先只改地点）', style: TextStyle(fontSize: 13.5)),
              onTap: () => Navigator.pop(ctx, '__none__'),
            ),
            const SizedBox(height: 6),
          ],
        ),
      ),
    );
    if (choice == null) return null;
    if (choice == '__keep__') return (o.timeSlotStart, o.timeSlotEnd);
    if (choice == '__none__') return (null, null);
    final picked = options[choice]!;
    return (picked.$1, picked.$2);
  }

  /// 状态码含义（与后端一致）：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已拒绝 / 5 已取消
  Widget _actions(MeetupOrder o, bool isBuyer) {
    final buttons = <Widget>[];
    // **只有卖家能定面交地点与时间**（后端 updatePoint 强校验 sellerId）。
    // 买家这一侧不放入口，想改就在私聊里跟卖家商量。
    if (!isBuyer && (o.status == 1 || o.status == 2)) {
      buttons.add(OutlinedButton(
        onPressed: () => _changeMeetup(o),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(104, 36),
          foregroundColor: AppColors.brandDark,
          side: const BorderSide(color: AppColors.brand),
        ),
        child: Text(o.pointName.isEmpty ? '定面交地点' : '改面交地点',
            style: const TextStyle(fontSize: 13)),
      ));
    }
    if (!isBuyer && o.status == 1) {
      buttons.add(FilledButton(
        onPressed: () => _act(
          () => ref.read(orderRepositoryProvider).accept(o.id),
          '已接受，约面交成功',
        ),
        style: FilledButton.styleFrom(minimumSize: const Size(96, 36)),
        child: const Text('接受面交', style: TextStyle(fontSize: 13)),
      ));
      buttons.add(OutlinedButton(
        onPressed: () => _reject(o.id),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(88, 36),
          foregroundColor: AppColors.inkSoft,
          side: const BorderSide(color: AppColors.line),
        ),
        child: const Text('拒绝', style: TextStyle(fontSize: 13)),
      ));
    }
    if (isBuyer && o.status == 2) {
      buttons.add(FilledButton(
        onPressed: () => _act(
          () => ref.read(orderRepositoryProvider).confirmOrder(o.id),
          '已确认收到，等卖家确认卖出',
        ),
        style: FilledButton.styleFrom(minimumSize: const Size(96, 36)),
        child: const Text('我已收到', style: TextStyle(fontSize: 13)),
      ));
    }
    if (!isBuyer && o.status == 2) {
      buttons.add(FilledButton(
        onPressed: () => _act(
          () => ref.read(orderRepositoryProvider).confirmOrder(o.id),
          '已成交，信用分 +2',
        ),
        style: FilledButton.styleFrom(minimumSize: const Size(110, 36)),
        child: const Text('已卖出（成交）', style: TextStyle(fontSize: 13)),
      ));
    }
    if (buttons.isEmpty) {
      return Text(
        '订单号 ' + o.orderNo,
        style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
      );
    }
    // 用 Wrap 而不是 Row：卖家在状态 1 会有三个按钮（改面交地点 / 接受面交 / 拒绝），
    // 固定 Row 在 720px 宽的真机上会溢出 40px 把"拒绝"挤出屏幕（实测带黄黑警告条）。
    // Wrap 放不下就换行，按钮多了也不会挤爆。
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 10,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: buttons,
        ),
        const SizedBox(height: 6),
        Text(
          Fmt.timeAgo(o.createdAt),
          style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
        ),
      ],
    );
  }

  Future<void> _reject(int orderId) async {
    final controller = TextEditingController(text: '时间不合适');
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('拒绝这次面交'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(labelText: '原因（会发给买家）'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: const Text('确定', style: TextStyle(color: AppColors.price)),
          ),
        ],
      ),
    );
    if (reason == null) return;
    await _act(
      () => ref.read(orderRepositoryProvider).reject(orderId, reason),
      '已拒绝',
    );
  }
}