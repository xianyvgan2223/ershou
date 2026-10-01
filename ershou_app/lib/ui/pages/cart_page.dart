import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'point_picker_page.dart';

/// 购物车：按卖家分组，勾选后「一键合并面交」。
///
/// 与 Web 端同一套语义：
/// - **只有「在售」能结算**，已锁定 / 已售出 / 已下架不可勾选（点它会说明原因）；
/// - **每件商品拆一张面交单**，同一个卖家的多件也是多张单，只是共用一个批次号；
/// - 结算时若某件已不可面交，**不会拖垮整批**：能约的照常提交，其余由后端回报在
///   `skipped` 里，这里弹一个"未提交"清单说清楚是哪件、为什么。
class CartPage extends ConsumerStatefulWidget {
  const CartPage({super.key});

  @override
  ConsumerState<CartPage> createState() => _CartPageState();
}

class _CartPageState extends ConsumerState<CartPage> {
  List<CartGroup> _groups = const [];
  bool _loading = true;
  String? _error;
  bool _busy = false;
  /// 勾选的购物车条目 id
  final Set<int> _selected = {};
  /// 已经出现过的条目：新加入的自动勾选，老条目尊重用户的手动操作
  final Set<int> _known = {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  List<CartRow> get _allRows => _groups.expand((g) => g.items).toList();
  List<CartRow> get _sellable => _allRows.where((r) => r.sellable).toList();
  List<CartRow> get _selectedRows =>
      _allRows.where((r) => _selected.contains(r.cartId)).toList();
  double get _total => _selectedRows.fold<double>(0, (s, r) => s + r.subtotal);

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final groups = await ref.read(cartRepositoryProvider).groups();
      if (!mounted) return;
      setState(() {
        _groups = groups;
        _loading = false;
      });
      _syncSelection();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  /// 新出现的可面交条目默认勾上；用户手动取消过的不再自动勾回来
  void _syncSelection() {
    final sellableIds = _sellable.map((r) => r.cartId).toSet();
    _selected.removeWhere((id) => !sellableIds.contains(id));
    for (final id in sellableIds) {
      if (!_known.contains(id)) _selected.add(id);
    }
    for (final row in _allRows) {
      _known.add(row.cartId);
    }
    if (mounted) setState(() {});
  }

  bool _groupChecked(CartGroup g) {
    final ids = g.items.where((r) => r.sellable).map((r) => r.cartId).toList();
    return ids.isNotEmpty && ids.every(_selected.contains);
  }

  void _toggleGroup(CartGroup g, bool checked) {
    final ids = g.items.where((r) => r.sellable).map((r) => r.cartId).toList();
    setState(() {
      if (checked) {
        _selected.addAll(ids);
      } else {
        _selected.removeAll(ids);
      }
    });
  }

  void _toggleRow(CartRow row) {
    if (!row.sellable) {
      showToast(context, '「${row.title}」${row.statusText.isEmpty ? '当前不可面交' : row.statusText}，无法结算');
      return;
    }
    setState(() {
      if (!_selected.remove(row.cartId)) _selected.add(row.cartId);
    });
  }

  /// 改数量：调用后端后重新拉列表（后端会做夹取：孤品恒 1、不超库存）
  Future<void> _changeQuantity(CartRow row, int next) async {
    if (next < 1) return;
    try {
      await ref.read(cartRepositoryProvider).updateQuantity(row.cartId, next);
      await _load();
      await ref.read(cartCountProvider.notifier).refresh();
    } catch (e) {
      // 后端会拒（超库存等），把原因告诉用户并把列表拉回真实状态
      if (mounted) showToast(context, e.toString());
      await _load();
    }
  }

  Future<void> _remove(CartRow row) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('移除商品'),
        content: Text('确定把「${row.title}」从购物车移除吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('移除')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(cartRepositoryProvider).remove(row.cartId);
      if (mounted) showToast(context, '已移除');
      await _load();
      await ref.read(cartCountProvider.notifier).refresh();
    } catch (e) {
      if (mounted) showToast(context, '移除失败：$e');
    }
  }

  /// 一键合并面交：先选面交点（可跳过=待协商），再提交
  Future<void> _checkout() async {
    if (_selected.isEmpty) {
      // 一件没勾时把可面交的都勾上：用户点这个按钮就是明确意图
      final ids = _sellable.map((r) => r.cartId).toList();
      if (ids.isEmpty) {
        showToast(context, '购物车里没有可面交的商品（已锁定 / 已售出 / 已下架）');
        return;
      }
      setState(() => _selected.addAll(ids));
    }
    final point = await Navigator.of(context).push<MapPoint>(
      MaterialPageRoute<MapPoint>(
        builder: (_) => const PointPickerPage(pickExisting: true, title: '选面交点'),
      ),
    );
    // 用户直接返回 = 不想选点位，按"待协商"继续（面交点本来就是选填）
    if (!mounted) return;
    await _submit(point?.id);
  }

  Future<void> _submit(int? pointId) async {
    setState(() => _busy = true);
    try {
      final result = await ref.read(cartRepositoryProvider).checkout(
            cartIds: _selected.toList(),
            pointId: pointId,
          );
      if (!mounted) return;
      final orders = (result['orders'] as List?) ?? const [];
      final skipped = ((result['skipped'] as List?) ?? const [])
          .whereType<Map>()
          .map((e) => CheckoutSkipped.fromJson(e.cast<String, dynamic>()))
          .toList();
      // 新加进来的条目要重新自动勾选，所以清掉"已知"记录
      _known.clear();
      _selected.clear();
      await _load();
      await ref.read(cartCountProvider.notifier).refresh();
      if (!mounted) return;
      await _showResult(orders.length, skipped);
    } catch (e) {
      if (mounted) showToast(context, '结算失败：$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _showResult(int orderCount, List<CheckoutSkipped> skipped) {
    return showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(orderCount > 0 ? '已提交 $orderCount 张面交单' : '没有可提交的商品'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('等待卖家确认，确认后商品会为你锁定。',
                style: TextStyle(fontSize: 13, color: AppColors.inkSoft, height: 1.5)),
            if (skipped.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Text('以下商品已不可面交，本次没有提交：',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              ...skipped.map((s) => Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text('· ${s.title} —— ${s.reason}',
                        style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft, height: 1.5)),
                  )),
            ],
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('继续逛逛')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(title: const Text('购物车')),
      body: _body(),
      bottomNavigationBar: _groups.isEmpty ? null : _bar(),
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_groups.isEmpty) {
      return EmptyState(
        icon: Icons.shopping_cart_outlined,
        message: _error ?? '购物车还是空的，去挑几件好物吧',
        onRetry: _error == null ? null : () => _load(),
      );
    }
    // 用 Column + SingleChildScrollView：ListView 在这个页面里表现异常（整块渲染不出来），
    // 换成显式滚动容器，购物车条目数本来也不多，不需要懒加载。
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 顶部小提示：说清"合并面交"是怎么回事
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            margin: const EdgeInsets.only(bottom: 10),
            decoration: BoxDecoration(
              color: AppColors.brandSoft,
              borderRadius: BorderRadius.circular(AppRadius.field),
            ),
            child: const Text(
              '勾选同一面交点、同一时间窗的商品，可一键合并成面交单（每件一张，共用一个批次号）',
              style: TextStyle(fontSize: 12, color: AppColors.brandDark, height: 1.5),
            ),
          ),
          ..._groups.map(_groupCard),
        ],
      ),
    );
  }

  Widget _groupCard(CartGroup group) {
    final sellableCount = group.items.where((r) => r.sellable).length;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(AppRadius.card),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        children: [
          // 卖家行：勾选整组
          Padding(
            padding: const EdgeInsets.fromLTRB(10, 10, 14, 6),
            child: Row(
              children: [
                Checkbox(
                  value: _groupChecked(group),
                  onChanged: sellableCount == 0 ? null : (v) => _toggleGroup(group, v == true),
                  visualDensity: VisualDensity.compact,
                ),
                Expanded(
                  child: Text(
                    '卖家：${group.sellerName}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600),
                  ),
                ),
                Text('${group.items.length} 件',
                    style: const TextStyle(fontSize: 12, color: AppColors.inkFaint)),
              ],
            ),
          ),
          const Divider(height: 1, color: AppColors.line),
          ...group.items.map(_row),
        ],
      ),
    );
  }

  Widget _row(CartRow row) {
    final checked = _selected.contains(row.cartId);
    return InkWell(
      onTap: () => _toggleRow(row),
      child: Container(
        padding: const EdgeInsets.fromLTRB(6, 10, 12, 10),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Checkbox(
              value: checked,
              onChanged: row.sellable ? (_) => _toggleRow(row) : null,
              visualDensity: VisualDensity.compact,
            ),
            Opacity(
              opacity: row.sellable ? 1 : 0.6,
              child: NetImage(url: row.coverUrl, width: 62, height: 62, radius: 8),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    row.title.isEmpty ? '商品' : row.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13.5,
                      height: 1.35,
                      color: row.sellable ? AppColors.ink : AppColors.inkSoft,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      PriceText(value: Fmt.price(row.price), size: 14),
                      if (!row.sellable) ...[
                        const SizedBox(width: 6),
                        TagChip(
                          text: row.statusText.isEmpty ? '不可面交' : row.statusText,
                          color: AppColors.inkSoft,
                          background: AppColors.bg,
                        ),
                      ],
                    ],
                  ),
                  if (row.preferPointName != null && row.preferPointName!.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Text('面交点：${row.preferPointName}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint)),
                    ),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      _stepper(row),
                      const Spacer(),
                      TextButton(
                        onPressed: () => _remove(row),
                        style: TextButton.styleFrom(
                          foregroundColor: AppColors.inkSoft,
                          padding: EdgeInsets.zero,
                          minimumSize: const Size(0, 28),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        child: const Text('移除', style: TextStyle(fontSize: 12.5)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 数量步进器：孤品（quantity 固定 1 且不可加）直接禁用
  Widget _stepper(CartRow row) {
    final canChange = row.sellable;
    return Row(
      children: [
        _stepBtn(Icons.remove, canChange && row.quantity > 1 ? () => _changeQuantity(row, row.quantity - 1) : null),
        Container(
          width: 38,
          alignment: Alignment.center,
          child: Text('${row.quantity}', style: const TextStyle(fontSize: 13.5)),
        ),
        _stepBtn(Icons.add, canChange ? () => _changeQuantity(row, row.quantity + 1) : null),
      ],
    );
  }

  Widget _stepBtn(IconData icon, VoidCallback? onTap) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(6),
      child: Container(
        width: 26,
        height: 26,
        decoration: BoxDecoration(
          border: Border.all(color: AppColors.line),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Icon(icon, size: 15,
            color: onTap == null ? AppColors.inkFaint : AppColors.inkSoft),
      ),
    );
  }

  /// 底部结算条
  Widget _bar() {
    final allChecked = _sellable.isNotEmpty && _selected.length == _sellable.length;
    return Container(
      padding: EdgeInsets.only(
        left: 10,
        right: 12,
        top: 8,
        bottom: MediaQuery.of(context).padding.bottom + 8,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        children: [
          Checkbox(
            value: allChecked,
            onChanged: _sellable.isEmpty
                ? null
                : (v) => setState(() {
                      if (v == true) {
                        _selected.addAll(_sellable.map((r) => r.cartId));
                      } else {
                        _selected.clear();
                      }
                    }),
            visualDensity: VisualDensity.compact,
          ),
          const Text('全选', style: TextStyle(fontSize: 13)),
          const Spacer(),
          Column(
            // ⚠️ 必须 mainAxisSize.min：Row 里的 Column 默认按最大高度撑开，
            // 放进 bottomNavigationBar 会把 Scaffold 的可用高度全吃掉 ——
            // body / AppBar 被挤成 0 高，整页只剩这条栏（实测白屏，排查了很久）。
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Row(
                children: [
                  const Text('合计 ', style: TextStyle(fontSize: 12, color: AppColors.inkSoft)),
                  PriceText(value: Fmt.price(_total), size: 17),
                ],
              ),
              Text('已选 ${_selected.length} / ${_sellable.length} 件',
                  style: const TextStyle(fontSize: 11, color: AppColors.inkFaint)),
            ],
          ),
          const SizedBox(width: 10),
          FilledButton(
            onPressed: _busy ? null : _checkout,
            child: _busy
                ? const SizedBox(
                    width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('合并面交'),
          ),
        ],
      ),
    );
  }
}
