import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/storage/image_cache.dart';
import '../theme/app_theme.dart';

/// 统一处理图片地址：后端返回的 MinIO 绝对地址会重写到当前服务器。
class NetImage extends ConsumerWidget {
  const NetImage({
    super.key,
    required this.url,
    this.fit = BoxFit.cover,
    this.width,
    this.height,
    this.radius = 12,
  });

  final String? url;
  final BoxFit fit;
  final double? width;
  final double? height;
  final double radius;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final resolved = ref.watch(itemRepositoryProvider).imageUrl(url);
    // 列表缩略图按显示尺寸解码：一张 3000px 的原图直接进内存 = 36MB，
    // 缩到实际像素宽后同样的列表内存占用能降一个数量级。
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final decodeWidth = (width == null || width! <= 0)
        ? null
        : (width! * dpr).round().clamp(1, 1600);
    Widget fallback(IconData icon) => Container(
          width: width,
          height: height,
          color: AppColors.bg,
          alignment: Alignment.center,
          child: Icon(icon, color: AppColors.inkFaint, size: 26),
        );

    Widget child;
    if (resolved.isEmpty) {
      child = fallback(Icons.image_outlined);
    } else {
      child = CachedNetworkImage(
        imageUrl: resolved,
        cacheManager: ErshouImageCache.instance,
        memCacheWidth: decodeWidth,
        width: width,
        height: height,
        fit: fit,
        fadeInDuration: const Duration(milliseconds: 180),
        placeholder: (_, __) => Container(
          width: width,
          height: height,
          color: AppColors.bg,
          alignment: Alignment.center,
          child: const SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
        errorWidget: (_, __, ___) => fallback(Icons.broken_image_outlined),
      );
    }
    if (radius <= 0) return child;
    return ClipRRect(borderRadius: BorderRadius.circular(radius), child: child);
  }
}

/// 头像：没图就显示昵称首字，比灰头像好看。
class Avatar extends ConsumerWidget {
  const Avatar({super.key, this.url, this.name, this.size = 40});

  final String? url;
  final String? name;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final resolved = ref.watch(itemRepositoryProvider).imageUrl(url);
    final initial = (name == null || name!.isEmpty) ? '?' : name!.characters.first;
    Widget letter() => Container(
          width: size,
          height: size,
          decoration: const BoxDecoration(color: AppColors.brandSoft, shape: BoxShape.circle),
          alignment: Alignment.center,
          child: Text(
            initial,
            style: TextStyle(
              color: AppColors.brandDark,
              fontSize: size * 0.4,
              fontWeight: FontWeight.w600,
            ),
          ),
        );
    if (resolved.isEmpty) return letter();
    return ClipOval(
      child: CachedNetworkImage(
        imageUrl: resolved,
        cacheManager: ErshouImageCache.instance,
        memCacheWidth: (size * MediaQuery.devicePixelRatioOf(context)).round().clamp(1, 512),
        width: size,
        height: size,
        fit: BoxFit.cover,
        errorWidget: (_, __, ___) => letter(),
      ),
    );
  }
}
