import 'dart:convert';

/// 与后端 VO 对齐的数据模型（只声明 App 用得上的字段）。
class PageResult<T> {
  const PageResult({
    required this.total,
    required this.pageNum,
    required this.pageSize,
    required this.records,
  });

  final int total;
  final int pageNum;
  final int pageSize;
  final List<T> records;

  bool get hasMore => pageNum * pageSize < total;

  static PageResult<T> fromJson<T>(
      Map<String, dynamic> json, T Function(Map<String, dynamic>) item) {
    final rawRecords = (json['records'] as List?) ?? const [];
    return PageResult<T>(
      total: (json['total'] as num?)?.toInt() ?? 0,
      pageNum: (json['pageNum'] as num?)?.toInt() ?? 1,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? rawRecords.length,
      records: rawRecords
          .whereType<Map>()
          .map((e) => item(e.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class Seller {
  const Seller({
    required this.id,
    this.nickname = '',
    this.avatar = '',
    this.creditScore = 0,
    this.shopName = '',
    this.schoolName = '',
    this.campusName = '',
  });

  final int id;
  final String nickname;
  final String avatar;
  final int creditScore;
  final String shopName;
  final String schoolName;
  final String campusName;

  factory Seller.fromJson(Map<String, dynamic> json) => Seller(
        id: (json['id'] as num?)?.toInt() ?? 0,
        nickname: (json['nickname'] as String?) ?? '',
        avatar: (json['avatar'] as String?) ?? '',
        creditScore: (json['creditScore'] as num?)?.toInt() ?? 0,
        shopName: (json['shopName'] as String?) ?? '',
        schoolName: (json['schoolName'] as String?) ?? '',
        campusName: (json['campusName'] as String?) ?? '',
      );
}

class Item {
  const Item({
    required this.id,
    this.title = '',
    this.description = '',
    this.price = 0,
    this.originalPrice,
    this.negotiable = 0,
    this.conditionLevel,
    this.stock = 1,
    this.isUnique,
    this.conditionText = '',
    this.coverUrl = '',
    this.images = const [],
    this.categoryId,
    this.categoryName = '',
    this.campusId,
    this.preferPointId,
    this.preferPointName = '',
    this.preferPointGuide = '',
    this.status = 0,
    this.statusText = '',
    this.viewCount = 0,
    this.favCount = 0,
    this.favorited = false,
    this.publishedAt = '',
    this.seller,
    this.highlightTitle,
  });

  final int id;
  final String title;
  final String description;
  final double price;
  final double? originalPrice;
  final int negotiable;
  final int? conditionLevel;
  final int stock;
  final int? isUnique;
  final String conditionText;
  final String coverUrl;
  final List<String> images;
  final int? categoryId;
  final String categoryName;
  final int? campusId;
  final int? preferPointId;
  final String preferPointName;
  final String preferPointGuide;
  final int status;
  final String statusText;
  final int viewCount;
  final int favCount;
  final bool favorited;
  final String publishedAt;
  final Seller? seller;
  final String? highlightTitle;

  /// 后端 status=2 且 statusText="在售"（已用真实接口核对）；用文案判断最稳，避免猜错枚举。
  bool get onSale => statusText == '在售' || (statusText.isEmpty && status == 2);

  factory Item.fromJson(Map<String, dynamic> json) => Item(
        id: (json['id'] as num?)?.toInt() ?? 0,
        title: (json['title'] as String?) ?? '',
        description: (json['description'] as String?) ?? '',
        price: _toDouble(json['price']),
        originalPrice: json['originalPrice'] == null ? null : _toDouble(json['originalPrice']),
        negotiable: (json['negotiable'] as num?)?.toInt() ?? 0,
        conditionLevel: (json['conditionLevel'] as num?)?.toInt(),
        stock: (json['stock'] as num?)?.toInt() ?? 1,
        isUnique: (json['isUnique'] as num?)?.toInt(),
        conditionText: (json['conditionText'] as String?) ?? '',
        coverUrl: (json['coverUrl'] as String?) ?? '',
        images: ((json['images'] as List?) ?? const [])
            .map((e) => e?.toString() ?? '')
            .where((e) => e.isNotEmpty)
            .toList(),
        categoryId: (json['categoryId'] as num?)?.toInt(),
        categoryName: (json['categoryName'] as String?) ?? '',
        campusId: (json['campusId'] as num?)?.toInt(),
        preferPointId: (json['preferPointId'] as num?)?.toInt(),
        preferPointName: (json['preferPointName'] as String?) ?? '',
        preferPointGuide: (json['preferPointGuide'] as String?) ?? '',
        status: (json['status'] as num?)?.toInt() ?? 0,
        statusText: (json['statusText'] as String?) ?? '',
        viewCount: (json['viewCount'] as num?)?.toInt() ?? 0,
        favCount: (json['favCount'] as num?)?.toInt() ?? 0,
        favorited: (json['favorited'] as bool?) ?? false,
        publishedAt: (json['publishedAt'] as String?) ?? '',
        seller: json['seller'] is Map
            ? Seller.fromJson((json['seller'] as Map).cast<String, dynamic>())
            : null,
        highlightTitle: json['highlightTitle'] as String?,
      );

  /// 只用于局部更新（目前只有收藏态），其余字段原样带过来。
  Item copyWith({bool? favorited, int? favCount}) => Item(
        id: id,
        title: title,
        description: description,
        price: price,
        originalPrice: originalPrice,
        negotiable: negotiable,
        conditionLevel: conditionLevel,
        stock: stock,
        isUnique: isUnique,
        conditionText: conditionText,
        coverUrl: coverUrl,
        images: images,
        categoryId: categoryId,
        categoryName: categoryName,
        campusId: campusId,
        preferPointId: preferPointId,
        preferPointName: preferPointName,
        preferPointGuide: preferPointGuide,
        status: status,
        statusText: statusText,
        viewCount: viewCount,
        favCount: favCount ?? this.favCount,
        favorited: favorited ?? this.favorited,
        publishedAt: publishedAt,
        seller: seller,
        highlightTitle: highlightTitle,
      );

  static double _toDouble(Object? v) {
    if (v == null) return 0;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString()) ?? 0;
  }
}

class Category {
  const Category({required this.id, required this.name, this.sort = 0});

  final int id;
  final String name;
  final int sort;

  factory Category.fromJson(Map<String, dynamic> json) => Category(
        id: (json['id'] as num?)?.toInt() ?? 0,
        name: (json['name'] as String?) ?? '',
        sort: (json['sort'] as num?)?.toInt() ?? 0,
      );

  Map<String, dynamic> toJson() => {'id': id, 'name': name, 'sort': sort};
}

class AppUser {
  const AppUser({
    required this.id,
    this.nickname = '',
    this.avatar = '',
    this.phone = '',
    this.schoolName = '',
    this.campusName = '',
    this.creditScore = 0,
    this.shopName = '',
    this.publishLimit = 0,
    this.roleFlags = 0,
    this.isAdmin = false,
  });

  final int id;
  final String nickname;
  final String avatar;
  final String phone;
  final String schoolName;
  final String campusName;
  final int creditScore;
  final String shopName;

  /// 剩余发布额度（后端 publishLimit，个人页展示用）
  final int publishLimit;

  /// 1 普通 / 2 商家 / 4 代卖员 / 8 运营
  final int roleFlags;
  final bool isAdmin;

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: (json['id'] as num?)?.toInt() ?? 0,
        nickname: (json['nickname'] as String?) ?? '',
        avatar: (json['avatar'] as String?) ?? '',
        phone: (json['phone'] as String?) ?? '',
        schoolName: (json['schoolName'] as String?) ?? '',
        campusName: (json['campusName'] as String?) ?? '',
        creditScore: (json['creditScore'] as num?)?.toInt() ?? 0,
        shopName: (json['shopName'] as String?) ?? '',
        publishLimit: (json['publishLimit'] as num?)?.toInt() ?? 0,
        roleFlags: (json['roleFlags'] as num?)?.toInt() ?? 0,
        isAdmin: (json['isAdmin'] as bool?) ?? false,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'nickname': nickname,
        'avatar': avatar,
        'phone': phone,
        'schoolName': schoolName,
        'campusName': campusName,
        'creditScore': creditScore,
        'shopName': shopName,
        'publishLimit': publishLimit,
        'roleFlags': roleFlags,
        'isAdmin': isAdmin,
      };

  /// 离线快照：冷启动先渲染昵称/头像，避免首页顶部先闪"同学"再跳真名。
  String encode() => jsonEncode(toJson());

  static AppUser? decode(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return null;
      final user = AppUser.fromJson(decoded.cast<String, dynamic>());
      return user.id == 0 ? null : user;
    } catch (_) {
      return null;
    }
  }
}

class LoginResult {
  const LoginResult({required this.token, this.user});

  final String token;
  final AppUser? user;

  factory LoginResult.fromJson(Map<String, dynamic> json) => LoginResult(
        token: (json['token'] as String?) ?? '',
        user: json['user'] is Map
            ? AppUser.fromJson((json['user'] as Map).cast<String, dynamic>())
            : null,
      );
}
