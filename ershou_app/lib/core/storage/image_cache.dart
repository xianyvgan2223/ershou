import 'package:flutter_cache_manager/flutter_cache_manager.dart';

/// 网络图片磁盘缓存配置。
///
/// cached_network_image 默认是「200 个文件 / 30 天」，商品图很容易把它撑满，
/// 表现为翻几页以后老图又要重新下载。这里放大到 800 个 / 60 天，
/// 并固定 key，方便「清理图片缓存」入口统一清理。
class ErshouImageCache {
  ErshouImageCache._();

  static const key = 'ershouImages';

  static final CacheManager instance = CacheManager(
    Config(
      key,
      stalePeriod: const Duration(days: 60),
      maxNrOfCacheObjects: 800,
      repo: JsonCacheInfoRepository(databaseName: key),
      fileService: HttpFileService(),
    ),
  );

  static Future<void> clear() => instance.emptyCache();
}
