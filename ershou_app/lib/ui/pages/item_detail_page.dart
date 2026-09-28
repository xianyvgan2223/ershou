import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';

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

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final item = await ref.read(itemRepositoryProvider).detail(widget.itemId);
      if (!mounted) return;
      setState(() {
        _item = item;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
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

  bool get _loggedIn => ref.read(authProvider).isLoggedIn;

  void _requireLogin() {
    showToast(context, '这个功能需要先登录');
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
            onPressed: () => _loggedIn ? showToast(context, '私聊功能下一步接入') : _requireLogin(),
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
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '建议面交点'),
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
            icon: item.favorited ? Icons.favorite : Icons.favorite_border,
            label: '收藏',
            color: item.favorited ? AppColors.price : AppColors.inkSoft,
            onTap: () => _loggedIn ? showToast(context, '收藏接口下一轮接入') : _requireLogin(),
          ),
          _actionIcon(
            icon: Icons.chat_bubble_outline,
            label: '私聊',
            onTap: () => _loggedIn ? showToast(context, '私聊功能下一步接入') : _requireLogin(),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: FilledButton(
              onPressed: onSale
                  ? () => _loggedIn ? showToast(context, '约面交下一轮接入') : _requireLogin()
                  : null,
              style: FilledButton.styleFrom(
                backgroundColor: onSale ? AppColors.brand : AppColors.inkFaint,
              ),
              child: Text(onSale ? '约面交' : '已售出'),
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
