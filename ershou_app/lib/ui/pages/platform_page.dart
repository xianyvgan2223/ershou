import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models.dart' show PageResult;
import '../../data/platform_models.dart';
import '../../data/platform_repository.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'item_detail_page.dart';

/// 平台严选（官方代卖）页面。
///
/// 第 4 轮用户需求："移动端的平台严选页面呢" —— Web 已有 `/platform` 专区，
/// App 这里补齐同能力：Hero 统计 + 代卖点筛选 + 限时秒杀 + 优惠券 + 严选好物 2 列网格。
///
/// 数据全部来自第 2 轮已实现的后端接口（设计 2.4），本页只读调用：
///  - 概览 / 代卖点 / 商品分页 / 券 / 秒杀场次 公开；
///  - 领券 / 抢购 / 直购 需要登录（未登录提示并回到登录页）。
///
/// 刻意不改 `providers.dart`：repository 由 `apiClientProvider` 就地构造，
/// 页面用局部状态（FutureBuilder 风格的手动状态机），不影响全局。
class PlatformPage extends ConsumerStatefulWidget {
  const PlatformPage({super.key});

  @override
  ConsumerState<PlatformPage> createState() => _PlatformPageState();
}

class _PlatformPageState extends ConsumerState<PlatformPage> {
  static const int _pageSize = 10;

  PlatformRepository get _repo => PlatformRepository(ref.read(apiClientProvider));

  final ScrollController _scroll = ScrollController();
  Timer? _ticker;

  // 页面级状态
  bool _firstLoading = true;
  String? _error;
  PlatformOverview? _overview;
  List<PlatformPoint> _points = const [];
  List<PlatformCoupon> _coupons = const [];
  FlashSale? _flash;
  int _remain = 0;

  // 商品列表状态（分页 + 代卖点筛选）
  List<PlatformItem> _items = const [];
  int _total = 0;
  int _pageNum = 1;
  int? _pointId;
  bool _itemsLoading = false;
  bool _loadingMore = false;
  String? _itemsError;
  int _itemsSeq = 0;

  // 操作中（防连点）
  final Set<int> _claiming = {};
  final Set<int> _grabbing = {};

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_maybeLoadMore);
    // 倒计时：本地每秒递减，结束显示「已结束」（服务端 remainSeconds 为秒）
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      final flash = _flash;
      if (flash == null || flash.status != 2 || _remain <= 0) return;
      setState(() => _remain -= 1);
    });
    _bootstrap();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  /* ---------------------------------------------------------------- 数据 */

  /// 首屏/下拉刷新：概览 + 代卖点 + 券 + 秒杀 + 商品第一页。
  Future<void> _bootstrap() async {
    setState(() {
      _firstLoading = true;
      _error = null;
      _itemsError = null;
    });
    try {
      final results = await Future.wait<Object?>([
        _repo.overview(),
        _repo.points(),
        _repo.coupons(),
        _repo.flashSale(),
        _repo.items(pageNum: 1, pageSize: _pageSize, pointId: _pointId),
      ]);
      if (!mounted) return;
      final page = results[4] as PageResult<PlatformItem>;
      setState(() {
        _overview = results[0] as PlatformOverview;
        _points = (results[1] as List<PlatformPoint>?) ?? const [];
        _coupons = (results[2] as List<PlatformCoupon>?) ?? const [];
        _flash = results[3] as FlashSale?;
        _remain = _flash?.remainSeconds ?? 0;
        _items = page.records;
        _total = page.total;
        _pageNum = 1;
        _firstLoading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _firstLoading = false;
        _error = e.message;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _firstLoading = false;
        _error = '加载失败，请检查服务器连接后重试';
      });
    }
  }

  /// 只刷新商品网格（切换代卖点时用，保留 Hero/秒杀/券区块）。
  Future<void> _reloadItems() async {
    final seq = ++_itemsSeq;
    setState(() {
      _itemsLoading = true;
      _itemsError = null;
    });
    try {
      final page = await _repo.items(pageNum: 1, pageSize: _pageSize, pointId: _pointId);
      if (!mounted || seq != _itemsSeq) return;
      setState(() {
        _items = page.records;
        _total = page.total;
        _pageNum = 1;
        _itemsLoading = false;
      });
    } on ApiException catch (e) {
      if (!mounted || seq != _itemsSeq) return;
      setState(() {
        _itemsLoading = false;
        _itemsError = e.message;
      });
    } catch (_) {
      if (!mounted || seq != _itemsSeq) return;
      setState(() {
        _itemsLoading = false;
        _itemsError = '商品加载失败，点我重试';
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || _itemsLoading || _items.length >= _total) return;
    final seq = _itemsSeq;
    setState(() => _loadingMore = true);
    try {
      final next = _pageNum + 1;
      final page = await _repo.items(pageNum: next, pageSize: _pageSize, pointId: _pointId);
      if (!mounted || seq != _itemsSeq) return;
      setState(() {
        _items = [..._items, ...page.records];
        _total = page.total;
        _pageNum = next;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
    }
  }

  void _maybeLoadMore() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (pos.pixels >= pos.maxScrollExtent - 420) {
      unawaited(_loadMore());
    }
  }

  void _selectPoint(int? id) {
    if (_pointId == id) return;
    setState(() {
      _pointId = id;
      _items = const [];
      _total = 0;
    });
    unawaited(_reloadItems());
  }

  Future<void> _reloadFlash() async {
    try {
      final flash = await _repo.flashSale();
      if (!mounted) return;
      setState(() {
        _flash = flash;
        _remain = flash?.remainSeconds ?? 0;
      });
    } catch (_) {
      // 刷新失败保留旧数据，不打断页面
    }
  }

  Future<void> _reloadCoupons() async {
    try {
      final coupons = await _repo.coupons();
      if (!mounted) return;
      setState(() => _coupons = coupons);
    } catch (_) {
      // 同上：非关键区块失败静默
    }
  }

  /* ---------------------------------------------------------------- 操作 */

  /// 未登录：提示后回到根路由（根路由在未登录时就是 `LoginPage`）。
  bool _requireLogin(String action) {
    if (ref.read(authProvider).isLoggedIn) return true;
    showToast(context, '请先登录后$action');
    Navigator.of(context).popUntil((route) => route.isFirst);
    return false;
  }

  Future<void> _claim(PlatformCoupon coupon) async {
    if (!_requireLogin('领取优惠券')) return;
    if (_claiming.contains(coupon.id)) return;
    setState(() => _claiming.add(coupon.id));
    try {
      final result = await _repo.claimCoupon(coupon.id);
      if (!mounted) return;
      showToast(context, result.success ? '领取成功，可在下单时使用' : (result.message.isEmpty ? '领取失败' : result.message));
      if (result.success) await _reloadCoupons();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (e) {
      if (mounted) showToast(context, '领取失败：$e');
    } finally {
      if (mounted) setState(() => _claiming.remove(coupon.id));
    }
  }

  Future<void> _grab(FlashSaleItem item) async {
    if (!_requireLogin('抢购')) return;
    if (_grabbing.contains(item.itemId)) return;
    setState(() => _grabbing.add(item.itemId));
    try {
      final result = await _repo.grab(item.itemId);
      if (!mounted) return;
      if (result.success) {
        final no = result.orderNo ?? '';
        showToast(context, no.isEmpty ? '抢购成功！' : '抢购成功，订单号 $no');
        await _reloadFlash();
      } else {
        showToast(context, result.message.isEmpty ? '抢购失败' : result.message);
      }
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (e) {
      if (mounted) showToast(context, '抢购失败：$e');
    } finally {
      if (mounted) setState(() => _grabbing.remove(item.itemId));
    }
  }

  void _openItem(int itemId) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => ItemDetailPage(itemId: itemId)),
    );
  }

  /* ---------------------------------------------------------------- 构建 */

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.verified_rounded, size: 18, color: _gold),
            SizedBox(width: 5),
            Text('平台严选'),
          ],
        ),
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: _bootstrap,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: _body(),
    );
  }

  Widget _body() {
    if (_firstLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null && _overview == null) {
      return Center(
        child: EmptyState(
          icon: Icons.wifi_off_rounded,
          message: _error!,
          onRetry: _bootstrap,
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _bootstrap,
      child: CustomScrollView(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(AppSpacing.page, 10, AppSpacing.page, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _hero(),
                  const SizedBox(height: 12),
                  if (_points.isNotEmpty) _pointsSection(),
                  if (_flash != null && _flash!.items.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _flashSection(),
                  ],
                  if (_coupons.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _couponsSection(),
                  ],
                  const SizedBox(height: 14),
                  SectionHeader(
                    title: '严选好物',
                    subtitle: _total > 0 ? '共 $_total 件' : null,
                  ),
                ],
              ),
            ),
          ),
          _itemsSliver(),
          _footerSliver(),
        ],
      ),
    );
  }

  /* ---------------------------------------------------------------- Hero */

  Widget _hero() {
    final overview = _overview;
    final claimable = _coupons.where((c) => c.claimable && !c.claimed).length;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 15, 16, 13),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppRadius.card),
        gradient: const LinearGradient(
          colors: [Color(0xFF101E30), Color(0xFF1D3A58)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: AppTheme.softShadow,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.verified_rounded, size: 19, color: _goldLight),
              const SizedBox(width: 6),
              const Expanded(
                child: Text(
                  '校园二手 · 官方严选',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: Colors.white,
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: const Color(0x1FFFFFFF),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: const Text(
                  '官方质检',
                  style: TextStyle(fontSize: 10.5, color: Color(0xFFFFE1A8)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              _heroStat('在售平台商品', (overview?.platformItemCount ?? 0).toString()),
              _heroDivider(),
              _heroStat('代卖点', (overview?.pointCount ?? 0).toString()),
              _heroDivider(),
              _heroStat('可领券', (claimable > 0 ? claimable : (overview?.couponCount ?? 0)).toString()),
            ],
          ),
          const SizedBox(height: 10),
          const Text(
            '官方质检 · 校内自提 · 平台担保，买得更放心',
            style: TextStyle(fontSize: 11, color: Color(0x99FFFFFF)),
          ),
        ],
      ),
    );
  }

  Widget _heroStat(String label, String value) => Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              value,
              style: const TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.w800,
                color: Colors.white,
                height: 1.1,
              ),
            ),
            const SizedBox(height: 3),
            Text(
              label,
              style: const TextStyle(fontSize: 11, color: Color(0xB3FFFFFF)),
            ),
          ],
        ),
      );

  Widget _heroDivider() => Container(
        width: 1,
        height: 28,
        margin: const EdgeInsets.symmetric(horizontal: 12),
        color: const Color(0x1FFFFFFF),
      );

  /* ---------------------------------------------------------------- 代卖点 */

  Widget _pointsSection() {
    PlatformPoint? selected;
    for (final p in _points) {
      if (p.id == _pointId) {
        selected = p;
        break;
      }
    }
    final point = selected;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: 34,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: _points.length + 1,
            separatorBuilder: (_, _) => const SizedBox(width: 6),
            itemBuilder: (_, index) {
              final isAll = index == 0;
              final p = isAll ? null : _points[index - 1];
              final isSelected = isAll ? _pointId == null : _pointId == p!.id;
              return _chip(
                label: isAll ? '全部代卖点' : p!.name,
                selected: isSelected,
                onTap: () => _selectPoint(isAll ? null : p!.id),
              );
            },
          ),
        ),
        if (point != null && (point.address.isNotEmpty || point.openHours.isNotEmpty))
          Padding(
            padding: const EdgeInsets.only(top: 7, left: 2, right: 2),
            child: Row(
              children: [
                const Icon(Icons.place_outlined, size: 13, color: AppColors.inkFaint),
                const SizedBox(width: 3),
                Expanded(
                  child: Text(
                    [
                      point.address,
                      if (point.openHours.isNotEmpty) '营业 ${point.openHours}',
                    ].where((e) => e.isNotEmpty).join(' · '),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _chip({
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return Material(
      color: selected ? AppColors.brand : Colors.white,
      borderRadius: BorderRadius.circular(AppRadius.chip),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.chip),
        onTap: onTap,
        child: Container(
          alignment: Alignment.center,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              color: selected ? Colors.white : AppColors.inkSoft,
            ),
          ),
        ),
      ),
    );
  }

  /* ---------------------------------------------------------------- 秒杀 */

  Widget _flashSection() {
    final flash = _flash!;
    final pending = flash.status == 1;
    final ended = flash.status == 3 || _remain <= 0;
    final countdownText = pending ? '未开始' : (ended ? '已结束' : _fmtRemain(_remain));
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(AppRadius.card),
        boxShadow: AppTheme.softShadow,
      ),
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
            child: Row(
              children: [
                const Icon(Icons.bolt_rounded, size: 19, color: AppColors.price),
                const SizedBox(width: 3),
                const Text(
                  '限时秒杀',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    flash.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: ended ? AppColors.bg : const Color(0xFFFFF0ED),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    countdownText,
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w700,
                      color: ended ? AppColors.inkFaint : AppColors.price,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ),
              ],
            ),
          ),
          SizedBox(
            height: 216,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
              itemCount: flash.items.length,
              separatorBuilder: (_, _) => const SizedBox(width: 10),
              itemBuilder: (_, i) => _flashCard(flash.items[i], ended || pending),
            ),
          ),
        ],
      ),
    );
  }

  Widget _flashCard(FlashSaleItem item, bool disabled) {
    final busy = _grabbing.contains(item.itemId);
    final soldOut = item.leftStock <= 0;
    final canGrab = !item.grabbed && !soldOut && !disabled && !busy;
    final pending = _flash?.status == 1;
    final ended = _flash?.status == 3 || _remain <= 0;
    final String label;
    if (item.grabbed) {
      label = '已抢购';
    } else if (soldOut) {
      label = '已抢光';
    } else if (disabled) {
      label = pending ? '未开始' : (ended ? '已结束' : '不可抢');
    } else if (busy) {
      label = '抢购中…';
    } else {
      label = '立即抢购';
    }
    return SizedBox(
      width: 132,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(AppRadius.card)),
            onTap: () => _openItem(item.itemId),
            child: Stack(
              children: [
                NetImage(url: item.coverUrl, width: 132, height: 96, radius: 0),
                const Positioned(
                  left: 5,
                  top: 5,
                  child: TagChip(
                    text: '秒杀',
                    color: Colors.white,
                    background: Color(0xCCFF4D2D),
                  ),
                ),
              ],
            ),
          ),
          // 内容区弹性化：Fixed 行 + 标题占剩余，字体缩放 1.0~1.3 都不溢出
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(2, 5, 2, 2),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Align(
                      alignment: Alignment.topLeft,
                      child: Text(
                        item.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w500,
                          color: AppColors.ink,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 2),
                  SizedBox(
                    height: 20,
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        PriceText(value: Fmt.price(item.seckillPrice), size: 14),
                        const SizedBox(width: 4),
                        if (item.originPrice > item.seckillPrice)
                          Flexible(
                            child: Text(
                              '￥${Fmt.price(item.originPrice)}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 10,
                                color: AppColors.inkFaint,
                                decoration: TextDecoration.lineThrough,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 3),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(999),
                    child: LinearProgressIndicator(
                      value: item.leftRatio,
                      minHeight: 5,
                      backgroundColor: AppColors.line,
                      color: AppColors.price,
                    ),
                  ),
                  const SizedBox(height: 2),
                  SizedBox(
                    height: 14,
                    child: Text(
                      '剩余 ${item.leftStock} 件',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10, color: AppColors.inkFaint),
                    ),
                  ),
                  const SizedBox(height: 4),
                  SizedBox(
                    height: 26,
                    child: FilledButton(
                      onPressed: canGrab ? () => _grab(item) : null,
                      style: FilledButton.styleFrom(
                        padding: EdgeInsets.zero,
                        minimumSize: const Size(0, 26),
                        textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                        disabledBackgroundColor: AppColors.line,
                        disabledForegroundColor: AppColors.inkFaint,
                      ),
                      child: Text(label),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /* ---------------------------------------------------------------- 优惠券 */

  Widget _couponsSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionHeader(title: '优惠券', subtitle: '先领券，下单更划算'),
        SizedBox(
          height: 92,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: _coupons.length,
            separatorBuilder: (_, _) => const SizedBox(width: 8),
            itemBuilder: (_, i) => _couponCard(_coupons[i]),
          ),
        ),
      ],
    );
  }

  Widget _couponCard(PlatformCoupon coupon) {
    final busy = _claiming.contains(coupon.id);
    final canClaim = !coupon.claimed && coupon.claimable && coupon.leftStock > 0 && !busy;
    final String label;
    if (coupon.claimed) {
      label = '已领取';
    } else if (canClaim) {
      label = busy ? '领取中…' : '领取';
    } else if (coupon.leftStock <= 0) {
      label = '已领完';
    } else {
      label = '不可领';
    }
    return Container(
      width: 216,
      padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(AppRadius.card),
        boxShadow: AppTheme.softShadow,
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  _couponValueText(coupon),
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w800,
                    color: AppColors.price,
                    height: 1.1,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  coupon.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: AppColors.inkSoft),
                ),
                const SizedBox(height: 2),
                Text(
                  '剩余 ${coupon.leftStock}/${coupon.totalStock} 张',
                  style: const TextStyle(fontSize: 10, color: AppColors.inkFaint),
                ),
              ],
            ),
          ),
          const SizedBox(width: 6),
          SizedBox(
            width: 62,
            height: 30,
            child: FilledButton(
              onPressed: canClaim ? () => _claim(coupon) : null,
              style: FilledButton.styleFrom(
                padding: EdgeInsets.zero,
                minimumSize: const Size(0, 30),
                textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                disabledBackgroundColor: AppColors.line,
                disabledForegroundColor: AppColors.inkFaint,
              ),
              child: Text(label),
            ),
          ),
        ],
      ),
    );
  }

  /// 券面额文案：满减「¥8」/ 折扣「9折」（后端折扣率可能下发 0.9 或 90）。
  String _couponValueText(PlatformCoupon coupon) {
    if (coupon.type == 2) {
      final raw = coupon.discountRate ?? 0;
      if (raw <= 0) return '折扣券';
      final zhe = raw <= 1 ? raw * 10 : raw / 10;
      final text = zhe == zhe.roundToDouble()
          ? zhe.toStringAsFixed(0)
          : zhe.toStringAsFixed(1);
      return '$text折';
    }
    return '¥${Fmt.price(coupon.discountAmount ?? 0)}';
  }

  /* ---------------------------------------------------------------- 商品网格 */

  Widget _itemsSliver() {
    if (_itemsLoading && _items.isEmpty) {
      return const SliverFillRemaining(
        hasScrollBody: false,
        child: SizedBox(
          height: 200,
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }
    if (_items.isEmpty) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: EmptyState(
          icon: _itemsError == null ? Icons.inbox_outlined : Icons.wifi_off_rounded,
          message: _itemsError ?? '暂无平台商品，过会儿再来看看',
          onRetry: _itemsError == null ? _bootstrap : _reloadItems,
        ),
      );
    }
    // 网格高度按「可用宽度算出的卡片宽」+「固定内容高」来定，不用 childAspectRatio。
    // 之前 childAspectRatio: 0.62 是按比例反推高度，360dp 宽度下算出来比内容少 ~1.1px →
    // 每张卡底部黄黑条纹 BOTTOM OVERFLOWED BY 1.1 PIXELS（不同机型/字体缩放还会变）。
    return SliverLayoutBuilder(
      builder: (context, constraints) {
        // constraints.crossAxisExtent 是整屏宽（本 sliver 外层没有 SliverPadding）
        final cardW = (constraints.crossAxisExtent - 2 * AppSpacing.page - AppSpacing.gap) / 2;
        return SliverPadding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.page, 0, AppSpacing.page, 0),
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: AppSpacing.gap,
              crossAxisSpacing: AppSpacing.gap,
              mainAxisExtent: cardW * 0.75 + _platformCardContentH,
            ),
            delegate: SliverChildBuilderDelegate(
              (_, index) {
                final item = _items[index];
                return _PlatformItemCard(
                  item: item,
                  onTap: () => _openItem(item.id),
                );
              },
              childCount: _items.length,
            ),
          ),
        );
      },
    );
  }

  Widget _footerSliver() {
    if (_items.isEmpty) {
      return const SliverToBoxAdapter(child: SizedBox.shrink());
    }
    return SliverToBoxAdapter(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(0, 12, 0, 20),
        child: Center(
          child: _loadingMore
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Text(
                  _items.length >= _total ? '— 到底了 —' : '上拉加载更多',
                  style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
                ),
        ),
      ),
    );
  }

  /* ---------------------------------------------------------------- 工具 */

  String _fmtRemain(int seconds) {
    final total = seconds < 0 ? 0 : seconds;
    final h = total ~/ 3600;
    final m = (total % 3600) ~/ 60;
    final s = total % 60;
    String two(int v) => v < 10 ? '0$v' : v.toString();
    return h > 0 ? '${two(h)}:${two(m)}:${two(s)}' : '${two(m)}:${two(s)}';
  }
}

/* ------------------------------------------------------------------ *
 * 严选好物卡片（与首页 ItemCard 同一视觉语言，但带代卖点名 + 金色角标；
 * 平台数据没有收藏态/卖家信息，所以不复用 ItemCard，避免误触收藏接口）。
 * ------------------------------------------------------------------ */

class _PlatformItemCard extends StatelessWidget {
  const _PlatformItemCard({required this.item, required this.onTap});

  final PlatformItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final soldOut = (item.stock ?? 0) <= 0;
    // 紧凑版（T22 用户反馈"卡片太大"）：封面 4:3，文字区更小更紧；
    // 文字区仍 Expanded 弹性，标题 Expanded 占剩余，价格行/代卖点行固定高度 ——
    // 任意标题长度、字体缩放 1.0~1.3 都不会溢出。
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(AppRadius.card),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AspectRatio(
              aspectRatio: 4 / 3,
              child: Stack(
                children: [
                  NetImage(url: item.coverUrl, radius: 0),
                  if (soldOut)
                    Positioned.fill(
                      child: Container(
                        color: Colors.black38,
                        alignment: Alignment.center,
                        child: const Text(
                          '已售罄',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  const Positioned(
                    left: 6,
                    top: 6,
                    child: TagChip(
                      text: '官方代卖',
                      color: _goldDark,
                      background: _goldLight,
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(8, 5, 8, 6),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Align(
                        alignment: Alignment.topLeft,
                        child: Text(
                          item.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12.5,
                            height: 1.25,
                            fontWeight: FontWeight.w500,
                            color: AppColors.ink,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 2),
                    SizedBox(
                      height: 22,
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          PriceText(value: Fmt.price(item.price), size: 17),
                          const SizedBox(width: 5),
                          if (item.originalPrice != null && item.originalPrice! > item.price)
                            Flexible(
                              child: Text(
                                '￥${Fmt.price(item.originalPrice!)}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 10.5,
                                  color: AppColors.inkFaint,
                                  decoration: TextDecoration.lineThrough,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 2),
                    SizedBox(
                      height: 13,
                      child: Row(
                        children: [
                          const Icon(Icons.place_outlined, size: 11, color: AppColors.inkFaint),
                          const SizedBox(width: 2),
                          Expanded(
                            child: Text(
                              item.pointName.isEmpty ? '官方严选' : item.pointName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 10.5, color: AppColors.inkFaint),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 严选好物卡片「封面以下的固定内容高」（T22 紧凑版）：
/// 文字区内边距 11 + 标题两行（12.5px，1.0~1.3 缩放下约 31~40）+ 价格行 22 + 代卖点行 13 + 间隙 4。
/// 最坏约 90px，取 104 留 ≥14px 余量（字体缩放 1.3 也不溢出）。
const double _platformCardContentH = 104;

const Color _gold = Color(0xFFE8A100);
const Color _goldLight = Color(0xFFF7D08A);
const Color _goldDark = Color(0xFF7A4E00);
