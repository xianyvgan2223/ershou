import 'package:flutter/material.dart';

import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import 'common.dart';
import 'net_image.dart';

/// 列表用的横向条目：图 + 标题 + 价格 + 状态/浏览量 + 右侧操作。
class ItemTile extends StatelessWidget {
  const ItemTile({super.key, required this.item, this.onTap, this.trailing});

  final Item item;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              NetImage(url: item.coverUrl, width: 84, height: 84, radius: 10),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      item.title,
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
                        PriceText(value: Fmt.price(item.price), size: 16),
                        const SizedBox(width: 8),
                        if (!item.onSale)
                          TagChip(
                            text: item.statusText.isEmpty ? '已下架' : item.statusText,
                            color: AppColors.inkSoft,
                          )
                        else if (item.categoryName.isNotEmpty)
                          TagChip(text: item.categoryName),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      Fmt.timeAgo(item.publishedAt) +
                          ' · 浏览 ' +
                          item.viewCount.toString() +
                          ' · 收藏 ' +
                          item.favCount.toString(),
                      style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                    ),
                  ],
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 8),
                trailing!,
              ],
            ],
          ),
        ),
      ),
    );
  }
}
