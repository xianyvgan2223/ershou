import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';

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
    final timeText = o.timeSlotStart.isEmpty
        ? '时间待协商'
        : (o.timeSlotStart + (o.timeSlotEnd.isEmpty ? '' : ' ~ ' + o.timeSlotEnd));
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

  /// 状态码含义（与后端一致）：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已拒绝 / 5 已取消
  Widget _actions(MeetupOrder o, bool isBuyer) {
    final buttons = <Widget>[];
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
    return Row(
      children: [
        for (var i = 0; i < buttons.length; i++) ...[
          if (i > 0) const SizedBox(width: 10),
          buttons[i],
        ],
        const Spacer(),
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