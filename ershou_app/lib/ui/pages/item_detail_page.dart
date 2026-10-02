import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'cart_page.dart';
import 'chat_page.dart';

class ItemDetailPage extends ConsumerStatefulWidget {
  const ItemDetailPage({super.key, required this.itemId});

  final int itemId;

  @override
  ConsumerState<ItemDetailPage> createState() => _ItemDetailPageState();
}

class _ItemDetailPageState extends ConsumerState<ItemDetailPage> {
  Item? _item;
  String? _error;
  bool _loading = true;
  int _page = 0;
  bool _descExpanded = false;
  bool _favorited = false;
  bool _favoriteBusy = false;
  bool _meetupBusy = false;
  /// 加入购物车进行中
  bool _cartBusy = false;
  /// 我在这件商品上已有的进行中面交单（1 待确认 / 2 待面交）：有就把按钮灰掉
  MeetupOrder? _myOrder;

  @override
  void initState() {
    super.initState();
    _load();
  }

  String get _cacheKey => 'item.' + widget.itemId.toString();

  /// 先本地后网络（stale-while-revalidate）：
  /// 详情页是最高频入口，每次进来都转一次圈太亏；缓存先渲染，网络回来再覆盖。
  Future<void> _load() async {
    if (_item == null) {
      _loadFromCache();
      if (!mounted) return;
      if (_item == null) {
        setState(() {
          _loading = true;
          _error = null;
        });
      }
    }
    try {
      final raw = await ref.read(itemRepositoryProvider).detailRaw(widget.itemId);
      if (!mounted) return;
      if (raw == null) throw ApiException(-3, '商品不存在或已下架');
      final item = Item.fromJson(raw);
      setState(() {
        _item = item;
        _favorited = item.favorited;
        _loading = false;
        _error = null;
      });
      unawaited(ref.read(localStoreProvider).setJsonCache(_cacheKey, raw));
      // 商品到手后再查"我约过没有"：按钮要能显示成"已约，等卖家确认"并灰掉
      unawaited(_loadMyOrder());
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (_item == null) _error = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (_item == null) _error = e.toString();
      });
    }
  }

  void _loadFromCache() {
    final raw = ref.read(localStoreProvider).jsonCache(_cacheKey);
    if (raw is! Map) return;
    try {
      final item = Item.fromJson(raw.cast<String, dynamic>());
      if (item.id == 0) return;
      setState(() {
        _item = item;
        _favorited = item.favorited;
        _loading = false;
      });
    } catch (_) {
      // 缓存坏了就当没有，网络请求照常
    }
  }

  bool get _loggedIn => ref.read(authProvider).isLoggedIn;

  void _requireLogin() {
    showToast(context, '这个功能需要先登录');
  }

  /// 收藏 / 取消收藏（乐观更新，失败回滚）。
  Future<void> _toggleFavorite() async {
    final item = _item;
    if (item == null) return;
    if (!_loggedIn) {
      _requireLogin();
      return;
    }
    if (_favoriteBusy) return;
    setState(() {
      _favoriteBusy = true;
      _favorited = !_favorited;
    });
    try {
      final repo = ref.read(itemWriteRepositoryProvider);
      if (_favorited) {
        await repo.addFavorite(item.id);
      } else {
        await repo.removeFavorite(item.id);
      }
      if (!mounted) return;
      setState(() => _favoriteBusy = false);
      showToast(context, _favorited ? '已收藏' : '已取消收藏');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _favorited = !_favorited;
        _favoriteBusy = false;
      });
      showToast(context, e.message);
    }
  }

  /// 从商品发起（或复用）会话，然后进聊天页。
  Future<void> _openChat() async {
    final item = _item;
    if (item == null) return;
    if (!_loggedIn) {
      _requireLogin();
      return;
    }
    try {
      final session = await ref.read(chatRepositoryProvider).openWithItem(item.id);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => ChatPage(session: session)),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      showToast(context, e.message);
    } catch (e) {
      if (!mounted) return;
      showToast(context, '打不开会话：' + e.toString());
    }
  }

  /// 约面交：创建面交单（默认待协商，具体时间在私聊里定）。
  Future<void> _createMeetup() async {
    final item = _item;
    if (item == null) return;
    if (!_loggedIn) {
      _requireLogin();
      return;
    }
    if (_meetupBusy) return;
    setState(() => _meetupBusy = true);
    try {
      // 买家提交意向**不决定面交地点与时间**：商品在卖家手上，由他定
      // （卖家在「我的面交 → 我卖的」里设），想改就在私聊里商量。
      await ref.read(orderRepositoryProvider).create(itemId: item.id);
      if (!mounted) return;
      setState(() => _meetupBusy = false);
      showToast(context, '已发起约面交，等卖家确认（面交点与时间由卖家定）');
      // 立刻刷新按钮状态：刚约过的商品按钮该灰掉，不能让用户再点一次
      unawaited(_loadMyOrder());
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _meetupBusy = false);
      showToast(context, e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() => _meetupBusy = false);
      showToast(context, '约面交失败：' + e.toString());
    }
  }

  /// 「约面交」按钮文案：约过之后要说清现在是什么状态，而不是继续显示"约面交"
  String _bookText(bool onSale, String closedText) =>
      bookButtonText(activeOrderStatus: _myOrder?.status, onSale: onSale, closedText: closedText);

  /// 查我在这件商品上有没有进行中的面交单（有就把按钮灰掉）
  Future<void> _loadMyOrder() async {
    final item = _item;
    if (item == null || !_loggedIn) {
      if (mounted) setState(() => _myOrder = null);
      return;
    }
    try {
      final order = await ref.read(orderRepositoryProvider).activeOrderOfItem(item.id);
      if (!mounted) return;
      setState(() => _myOrder = order);
    } catch (_) {
      // 查不到就当没有：不要因为一次探测失败就把「约面交」也禁掉
    }
  }

  /// 加入购物车：可以再挑几件一起"合并面交"。
  ///
  /// 后端在加购时就会校验商品能不能约（已锁定 / 已售出 / 自己的商品 / 超库存都会被拒），
  /// 所以这里只要把错误原文透出来即可，不用自己再判一遍。
  Future<void> _addToCart() async {
    final item = _item;
    if (item == null) return;
    if (!_loggedIn) {
      _requireLogin();
      return;
    }
    if (_cartBusy) return;
    setState(() => _cartBusy = true);
    try {
      await ref.read(cartRepositoryProvider).add(item.id);
      if (!mounted) return;
      setState(() => _cartBusy = false);
      // 购物车红点要立刻跟上，否则用户以为没加成功
      await ref.read(cartCountProvider.notifier).refresh();
      if (!mounted) return;
      showToast(context, '已加入购物车，可继续挑几件一起面交');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _cartBusy = false);
      showToast(context, e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() => _cartBusy = false);
      showToast(context, '加入购物车失败：' + e.toString());
    }
  }

  Future<void> _openCart() async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute<void>(builder: (_) => const CartPage()),
    );
    if (mounted) await ref.read(cartCountProvider.notifier).refresh();
  }

  @override
  Widget build(BuildContext context) {
    final item = _item;
    if (_loading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    if (item == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('商品详情')),
        body: EmptyState(
          icon: Icons.error_outline,
          message: _error ?? '商品不存在或已下架',
          onRetry: _load,
        ),
      );
    }
    final images = item.images.isEmpty
        ? (item.coverUrl.isEmpty ? <String>[] : <String>[item.coverUrl])
        : item.images;
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: RefreshIndicator(
        onRefresh: _load,
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              pinned: true,
              expandedHeight: 320,
              backgroundColor: Colors.white,
              surfaceTintColor: Colors.transparent,
              leading: const _CircleBackButton(),
              // 购物车入口：件数红点跟着走，能直接进购物车合并面交
              actions: [
                IconButton(
                  onPressed: _openCart,
                  tooltip: '购物车',
                  icon: Consumer(
                    builder: (context, ref, _) {
                      final count = ref.watch(cartCountProvider);
                      return Badge(
                        isLabelVisible: count > 0,
                        label: Text(count > 99 ? '99+' : '$count'),
                        child: const Icon(Icons.shopping_cart_outlined),
                      );
                    },
                  ),
                ),
              ],
              flexibleSpace: FlexibleSpaceBar(
                background: Stack(
                  fit: StackFit.expand,
                  children: [
                    PageView.builder(
                      itemCount: images.isEmpty ? 1 : images.length,
                      onPageChanged: (i) => setState(() => _page = i),
                      itemBuilder: (_, i) {
                        if (images.isEmpty) {
                          return const NetImage(url: null, radius: 0);
                        }
                        return NetImage(url: images[i], radius: 0, fit: BoxFit.cover);
                      },
                    ),
                    if (images.length > 1)
                      Positioned(
                        right: 12,
                        bottom: 12,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black45,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(
                            (_page + 1).toString() + ' / ' + images.length.toString(),
                            style: const TextStyle(color: Colors.white, fontSize: 12),
                          ),
                        ),
                      ),
                    if (!item.onSale)
                      Positioned.fill(
                        child: Container(
                          color: Colors.black38,
                          alignment: Alignment.center,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
                            decoration: BoxDecoration(
                              color: Colors.black54,
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: Text(
                              item.statusText.isEmpty ? '已下架' : item.statusText,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 18,
                                fontWeight: FontWeight.w700,
                                letterSpacing: 2,
                              ),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _priceCard(item),
                    const SizedBox(height: 12),
                    _sellerCard(item),
                    const SizedBox(height: 12),
                    _descriptionCard(item),
                    if (item.preferPointName.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      _meetupCard(item),
                    ],
                    const SizedBox(height: 90),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: _bottomBar(item),
    );
  }

  Widget _priceCard(Item item) {
    return AppCard(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              PriceText(value: Fmt.price(item.price), size: 26),
              const SizedBox(width: 8),
              if (item.originalPrice != null && item.originalPrice! > item.price)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    '原价 ￥' + Fmt.price(item.originalPrice!),
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.inkFaint,
                      decoration: TextDecoration.lineThrough,
                    ),
                  ),
                ),
              const Spacer(),
              Text(
                Fmt.timeAgo(item.publishedAt),
                style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            item.title,
            style: const TextStyle(
              fontSize: 17,
              height: 1.4,
              fontWeight: FontWeight.w600,
              color: AppColors.ink,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              if (item.statusText.isNotEmpty)
                TagChip(
                  text: item.statusText,
                  color: item.onSale ? AppColors.brandDark : AppColors.inkSoft,
                  background: item.onSale ? AppColors.brandSoft : AppColors.bg,
                ),
              if (item.conditionText.isNotEmpty) TagChip(text: item.conditionText),
              if (item.categoryName.isNotEmpty) TagChip(text: item.categoryName),
              if (item.negotiable == 1)
                const TagChip(
                  text: '可议价',
                  color: AppColors.price,
                  background: Color(0xFFFFF0EC),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Icon(Icons.visibility_outlined, size: 14, color: AppColors.inkFaint),
              const SizedBox(width: 4),
              Text(
                item.viewCount.toString() + ' 次浏览',
                style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
              ),
              const SizedBox(width: 16),
              const Icon(Icons.favorite_border, size: 14, color: AppColors.inkFaint),
              const SizedBox(width: 4),
              Text(
                item.favCount.toString() + ' 人收藏',
                style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _sellerCard(Item item) {
    final seller = item.seller;
    return AppCard(
      child: Row(
        children: [
          Avatar(url: seller?.avatar, name: seller?.nickname, size: 48),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  seller?.nickname ?? '匿名用户',
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: AppColors.brandSoft,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        '信用 ' + (seller?.creditScore ?? 0).toString(),
                        style: const TextStyle(fontSize: 11, color: AppColors.brandDark),
                      ),
                    ),
                    const SizedBox(width: 6),
                    if (seller != null && seller.campusName.isNotEmpty)
                      Text(
                        seller.campusName,
                        style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                      ),
                  ],
                ),
              ],
            ),
          ),
          OutlinedButton(
            onPressed: _openChat,
            style: OutlinedButton.styleFrom(minimumSize: const Size(0, 34)),
            child: const Text('私聊', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
    );
  }

  Widget _descriptionCard(Item item) {
    final text = item.description.isEmpty ? '卖家很懒，没有写描述。' : item.description;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '商品描述'),
          AnimatedCrossFade(
            duration: const Duration(milliseconds: 180),
            crossFadeState:
                _descExpanded ? CrossFadeState.showFirst : CrossFadeState.showSecond,
            firstChild: Text(text, style: const TextStyle(height: 1.7, fontSize: 14)),
            secondChild: Text(
              text,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(height: 1.7, fontSize: 14),
            ),
          ),
          if (text.length > 60)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => setState(() => _descExpanded = !_descExpanded),
                child: Text(
                  _descExpanded ? '收起' : '展开',
                  style: const TextStyle(fontSize: 13),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _meetupCard(Item item) {
    // "在地图上查看" 只在有面交点、且商品还能交易时给：
    // 点它会切到地图 Tab 并只显示这个点位（靠 mapFocusProvider 传递，
    // 因为地图是底部 Tab，没有路由参数可带）
    final canShowOnMap = item.preferPointId != null && item.preferPointId! > 0;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(child: SectionHeader(title: '建议面交点')),
              if (canShowOnMap)
                TextButton.icon(
                  onPressed: () {
                    ref.read(mapFocusProvider.notifier).focus(
                          item.preferPointId!,
                          name: item.preferPointName,
                        );
                    ref.read(navIndexProvider.notifier).select(2);
                    // ⚠️ 必须把详情页退掉：详情页是压在 Shell 之上的路由，
                    // 只切 Tab 的话地图在下面，用户看到的还是详情页，会以为"点了没反应"。
                    Navigator.of(context).pop();
                  },
                  icon: const Icon(Icons.map_outlined, size: 15),
                  label: const Text('在地图上查看', style: TextStyle(fontSize: 12.5)),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    minimumSize: const Size(0, 30),
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                ),
            ],
          ),
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: AppColors.brandSoft,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(Icons.place_outlined, color: AppColors.brandDark, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.preferPointName,
                      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                    ),
                    if (item.preferPointGuide.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        item.preferPointGuide,
                        style: const TextStyle(fontSize: 12, color: AppColors.inkSoft, height: 1.5),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _bottomBar(Item item) {
    final onSale = item.onSale;
    // 不能交易时按钮文案取后端下发的真实状态：已下架 ≠ 已售出
    final closedText = item.statusText.isEmpty ? '不可交易' : item.statusText;
    return Container(
      padding: EdgeInsets.only(
        left: 12,
        right: 12,
        top: 10,
        bottom: MediaQuery.of(context).padding.bottom + 10,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        children: [
          _actionIcon(
            icon: _favorited ? Icons.favorite : Icons.favorite_border,
            label: '收藏',
            color: _favorited ? AppColors.price : AppColors.inkSoft,
            onTap: _toggleFavorite,
          ),
          _actionIcon(
            icon: Icons.chat_bubble_outline,
            label: '私聊',
            color: onSale ? AppColors.inkSoft : AppColors.inkFaint,
            onTap: onSale ? _openChat : () => showToast(context, '商品' + closedText + '，不能再聊了'),
          ),
          _actionIcon(
            icon: Icons.add_shopping_cart,
            label: '加购',
            color: onSale ? AppColors.inkSoft : AppColors.inkFaint,
            onTap: onSale ? _addToCart : () => showToast(context, '商品' + closedText + '，不能加入购物车'),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: FilledButton(
              // 已经约过就灰掉（后端也做了防重，这里只是别让用户白点）
              onPressed: onSale && !_meetupBusy && _myOrder == null ? _createMeetup : null,
              style: FilledButton.styleFrom(
                backgroundColor: onSale && _myOrder == null ? AppColors.brand : AppColors.inkFaint,
              ),
              child: Text(_bookText(onSale, closedText)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _actionIcon({
    required IconData icon,
    required String label,
    Color color = AppColors.inkSoft,
    required VoidCallback onTap,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 22, color: color),
            const SizedBox(height: 2),
            Text(label, style: TextStyle(fontSize: 11, color: color)),
          ],
        ),
      ),
    );
  }
}

/// 图片上的返回按钮：永远是半透明黑底，避免浅色图上看不见。
class _CircleBackButton extends StatelessWidget {
  const _CircleBackButton();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Material(
        color: Colors.black38,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: () => Navigator.of(context).maybePop(),
          child: const SizedBox(
            width: 36,
            height: 36,
            child: Icon(Icons.arrow_back_ios_new, size: 16, color: Colors.white),
          ),
        ),
      ),
    );
  }
}

/// 「约面交」按钮的状态判定。
///
/// 抽成**纯函数**是为了能直接覆盖测试：以前这段逻辑埋在 State 里
/// （依赖 `_myOrder` 与 widget 构建），"约过之后按钮该不该灰"只能靠手点验证。
///
/// - `activeOrderStatus`：我在这件商品上进行中的面交单状态（1 待确认 / 2 待面交），没有就传 null
/// - 返回 (是否禁用, 按钮文案)
({bool disabled, String text}) bookButtonState({
  required int? activeOrderStatus,
  required bool onSale,
  required String closedText,
}) {
  if (activeOrderStatus != null) {
    // 已经约过：按钮灰掉，文案说清现在等谁
    return (
      disabled: true,
      text: activeOrderStatus == 2 ? '已约好，待面交' : '已约，等卖家确认',
    );
  }
  return (disabled: !onSale, text: onSale ? '约面交' : closedText);
}

/// 只要文案（按钮渲染用）
String bookButtonText({
  required int? activeOrderStatus,
  required bool onSale,
  required String closedText,
}) =>
    bookButtonState(
      activeOrderStatus: activeOrderStatus,
      onSale: onSale,
      closedText: closedText,
    ).text;