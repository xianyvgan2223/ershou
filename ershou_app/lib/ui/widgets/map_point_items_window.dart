import 'package:flutter/material.dart';

import '../../core/utils/formatters.dart';
import '../../data/models.dart';
import '../pages/item_detail_page.dart';
import '../theme/app_theme.dart';
import 'common.dart';
import 'net_image.dart';

/// 地图页的「点位商品小窗」：某个交易点下的在售商品，横向滑动。
///
/// 抽成独立组件是为了**能被 widget 测试直接覆盖** —— 它的高度是固定值，
/// 而"固定高度装不下内容"正是它出过的 bug（见下）。测试里用真机尺寸渲染一遍，
/// 一旦溢出 Flutter 会抛异常，测试立刻红。
///
/// ⚠️ 2026-10-02 修过的坑：小窗高度原本硬编码 132，而一张卡是
/// `图 76 + 间距 5 + 标题 14 + 间距 2 + 价格 21 = 118`，加上 ListView 的上下内边距 8×2
/// 一共 134 > 132 —— 真机上 Flutter 直接把 "BOTTOM OVERFLOWED BY 3.0 PIXELS"
/// 的黄黑条画在小窗上，价格那行被挤掉。用户看到的就是"小窗显示有问题"。
///
/// 现在的高度关系写成常量并互相引用（[miniCardHeight] = [contentHeight] + 内边距×2），
/// 改任何一个都不会再算漏；价格行也固定了高度并用 [FittedBox] 兜底，
/// 避免"字号 18 的行放不进 21 像素带子"这种隐性溢出。
class MapPointItemsWindow extends StatelessWidget {
  const MapPointItemsWindow({
    super.key,
    required this.items,
    required this.loading,
    this.onTapItem,
  });

  final List<Item> items;
  final bool loading;

  /// 点某件商品：默认进商品详情；测试里可注入回调避免真的跳页
  final void Function(Item item)? onTapItem;

  /// 卡片内边距（与小窗内 ListView 的上下 padding 保持一致）
  static const double listPadV = 8;

  /// 一张迷你卡的内容高度：图 76 + 间距 5 + 标题 14 + 间距 2 + 价格 21
  static const double contentHeight = 76 + 5 + 14 + 2 + 21;

  /// 单张卡的总高度（= 内容高度，卡片自身不加内边距，内边距在 ListView 上）
  static const double miniCardHeight = contentHeight;

  /// 小窗总高度 = 卡高 + 上下内边距
  static const double windowHeight = miniCardHeight + listPadV * 2;

  static const double _cardWidth = 86;

  @override
  Widget build(BuildContext context) {
    // 一件都没有：不占地方，直接不显示（点位信息条里已经写了"暂无在售"）
    if (!loading && items.isEmpty) return const SizedBox.shrink();

    return Material(
      color: Colors.white,
      elevation: 4,
      shadowColor: Colors.black26,
      borderRadius: BorderRadius.circular(12),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        height: windowHeight,
        child: loading
            ? const Center(
                child: SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              )
            : ListView.separated(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.fromLTRB(10, listPadV, 10, listPadV),
                itemCount: items.length,
                separatorBuilder: (_, __) => const SizedBox(width: 8),
                itemBuilder: (_, i) => _miniCard(context, items[i]),
              ),
      ),
    );
  }

  /// 迷你商品卡：图 + 标题一行 + 价格。整块可点，进商品详情。
  Widget _miniCard(BuildContext context, Item item) {
    return GestureDetector(
      onTap: () {
        if (onTapItem != null) {
          onTapItem!(item);
          return;
        }
        Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => ItemDetailPage(itemId: item.id)),
        );
      },
      child: SizedBox(
        width: _cardWidth,
        height: miniCardHeight,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            NetImage(url: item.coverUrl, width: _cardWidth, height: 76, radius: 8),
            const SizedBox(height: 5),
            // 标题固定占一行的高度：这样无论字体/文案怎么变，价格的位置都是确定的
            SizedBox(
              height: 14,
              child: Text(
                item.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 11.5,
                  height: 1.2,
                  fontWeight: FontWeight.w500,
                  color: AppColors.ink,
                ),
              ),
            ),
            const SizedBox(height: 2),
            // 价格行也固定高度：18 号字放不进这条 21 像素高的带子，缩到 15 并用 FittedBox 兜底
            SizedBox(
              height: 21,
              child: Align(
                alignment: Alignment.centerLeft,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: PriceText(value: Fmt.price(item.price), size: 15),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
