import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import 'common.dart';
import 'net_image.dart';

/// 首页瀑布卡片。
class ItemCard extends ConsumerWidget {
  const ItemCard({super.key, required this.item, this.onTap});

  final Item item;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sold = !item.onSale;
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(AppRadius.card),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
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
                      child: const Text(
                        '已售出',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                if (item.negotiable == 1 && !sold)
                  const Positioned(
                    left: 8,
                    top: 8,
                    child: TagChip(
                      text: '可议价',
                      color: Colors.white,
                      background: Color(0xCC00B578),
                    ),
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
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
                      if (item.originalPrice != null && item.originalPrice! > item.price)
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
                                style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Icon(Icons.visibility_outlined, size: 12, color: AppColors.inkFaint),
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
    );
  }
}
