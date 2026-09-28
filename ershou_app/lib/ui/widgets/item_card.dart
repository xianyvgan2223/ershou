import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import 'common.dart';
import 'net_image.dart';

/// 首页 / 列表页的商品卡片。
///
/// 封面右上角是收藏星（和网页端卡片一致）：点亮即收藏，再点取消。
/// 收藏是乐观更新 —— 先改 UI 再发请求，失败回滚并提示。
class ItemCard extends ConsumerStatefulWidget {
  const ItemCard({super.key, required this.item, this.onTap});

  final Item item;
  final VoidCallback? onTap;

  @override
  ConsumerState<ItemCard> createState() => _ItemCardState();
}

class _ItemCardState extends ConsumerState<ItemCard> {
  late bool _favorited = widget.item.favorited;
  bool _busy = false;

  @override
  void didUpdateWidget(covariant ItemCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 列表刷新 / 切换筛选后，以服务端返回的收藏态为准
    if (widget.item.id != oldWidget.item.id ||
        widget.item.favorited != oldWidget.item.favorited) {
      _favorited = widget.item.favorited;
    }
  }

  Future<void> _toggleFavorite() async {
    if (!ref.read(authProvider).isLoggedIn) {
      showToast(context, '登录后才能收藏商品');
      return;
    }
    if (_busy) return;
    final next = !_favorited;
    setState(() {
      _favorited = next;
      _busy = true;
    });
    try {
      final repo = ref.read(itemWriteRepositoryProvider);
      if (next) {
        await repo.addFavorite(widget.item.id);
      } else {
        await repo.removeFavorite(widget.item.id);
      }
      if (!mounted) return;
      setState(() => _busy = false);
      // 让列表状态、收藏页、详情页跟着更新
      ref.read(itemFeedProvider.notifier).setFavorited(widget.item.id, next);
      showToast(context, next ? '已加入收藏' : '已取消收藏');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _favorited = !next;
        _busy = false;
      });
      showToast(context, e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _favorited = !next;
        _busy = false;
      });
      showToast(context, '操作失败：' + e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final sold = !item.onSale;
    // 星星放在卡片的 Stack 外层（而不是嵌进卡片的 InkWell 里），
    // 免得两个手势识别器抢同一个点击：以前点星星边缘会穿透到卡片上跳详情页。
    return Stack(
      children: [
        Material(
          color: Colors.white,
          borderRadius: BorderRadius.circular(AppRadius.card),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
        onTap: widget.onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Stack(
              children: [
                AspectRatio(
                  aspectRatio: 1,
                  child: NetImage(url: item.coverUrl, radius: 0),
                ),
                if (sold)
                  Positioned.fill(
                    child: Container(
                      color: Colors.black38,
                      alignment: Alignment.center,
                      child: Text(
                        item.statusText.isEmpty ? '已下架' : item.statusText,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                if (item.negotiable == 1 && !sold)
                  const Positioned(
                    left: 7,
                    top: 7,
                    child: TagChip(
                      text: '可议价',
                      color: Colors.white,
                      background: Color(0xCC00B578),
                    ),
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(9, 7, 9, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      height: 1.3,
                      fontWeight: FontWeight.w500,
                      color: AppColors.ink,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      PriceText(value: Fmt.price(item.price)),
                      const SizedBox(width: 6),
                      if (item.originalPrice != null &&
                          item.originalPrice! > item.price)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 2),
                          child: Text(
                            '￥' + Fmt.price(item.originalPrice!),
                            style: const TextStyle(
                              fontSize: 11,
                              color: AppColors.inkFaint,
                              decoration: TextDecoration.lineThrough,
                            ),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Expanded(
                        child: Row(
                          children: [
                            Avatar(
                              url: item.seller?.avatar,
                              name: item.seller?.nickname,
                              size: 14,
                            ),
                            const SizedBox(width: 5),
                            Flexible(
                              child: Text(
                                item.seller?.nickname ?? '匿名',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: AppColors.inkFaint,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Icon(Icons.visibility_outlined,
                          size: 12, color: AppColors.inkFaint),
                      const SizedBox(width: 3),
                      Text(
                        Fmt.count(item.viewCount),
                        style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
        ),
        // 收藏星：浮在封面右上角（和网页端卡片一致）
        Positioned(top: 6, right: 6, child: _starButton()),
      ],
    );
  }

  /// 白色圆形底 + 五角星；点亮是实心橙色，未收藏是描边灰色。
  Widget _starButton() {
    // 外层 34×34 是点击热区（手指不好点 28），内层 28 是视觉尺寸
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _toggleFavorite,
      child: Container(
        width: 34,
        height: 34,
        alignment: Alignment.center,
        child: Container(
        width: 28,
        height: 28,
        alignment: Alignment.center,
        decoration: const BoxDecoration(
          color: Color(0xD9FFFFFF),
          shape: BoxShape.circle,
        ),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 150),
            transitionBuilder: (child, animation) =>
                ScaleTransition(scale: animation, child: child),
            child: Icon(
              _favorited ? Icons.star_rounded : Icons.star_border_rounded,
              key: ValueKey(_favorited),
              size: 19,
              color: _favorited ? const Color(0xFFFFA000) : AppColors.inkSoft,
            ),
          ),
        ),
      ),
    );
  }
}
