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
    this.conditionText = '',
    this.coverUrl = '',
    this.images = const [],
    this.categoryId,
    this.categoryName = '',
    this.campusId,
    this.preferPointName = '',
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
  final String conditionText;
  final String coverUrl;
  final List<String> images;
  final int? categoryId;
  final String categoryName;
  final int? campusId;
  final String preferPointName;
  final int status;
  final String statusText;
  final int viewCount;
  final int favCount;
  final bool favorited;
  final String publishedAt;
  final Seller? seller;
  final String? highlightTitle;

  bool get onSale => status == 1;

  factory Item.fromJson(Map<String, dynamic> json) => Item(
        id: (json['id'] as num?)?.toInt() ?? 0,
        title: (json['title'] as String?) ?? '',
        description: (json['description'] as String?) ?? '',
        price: _toDouble(json['price']),
        originalPrice: json['originalPrice'] == null ? null : _toDouble(json['originalPrice']),
        negotiable: (json['negotiable'] as num?)?.toInt() ?? 0,
        conditionText: (json['conditionText'] as String?) ?? '',
        coverUrl: (json['coverUrl'] as String?) ?? '',
        images: ((json['images'] as List?) ?? const [])
            .map((e) => e?.toString() ?? '')
            .where((e) => e.isNotEmpty)
            .toList(),
        categoryId: (json['categoryId'] as num?)?.toInt(),
        categoryName: (json['categoryName'] as String?) ?? '',
        campusId: (json['campusId'] as num?)?.toInt(),
        preferPointName: (json['preferPointName'] as String?) ?? '',
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
        isAdmin: (json['isAdmin'] as bool?) ?? false,
      );
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
