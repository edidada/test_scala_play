-- MySQL 8 development seed. Mirrors conf/sql/sqlite_seed.sql row-for-row.
-- Idempotent: INSERT IGNORE on the natural primary key of each row.

INSERT IGNORE INTO region(region_id,parent_id,region_name,region_type) VALUES(1,0,'中国',1);
INSERT IGNORE INTO region(region_id,parent_id,region_name,region_type) VALUES(2,1,'北京市',2);
INSERT IGNORE INTO region(region_id,parent_id,region_name,region_type) VALUES(52,2,'东城区',3);
INSERT IGNORE INTO region(region_id,parent_id,region_name,region_type) VALUES(500,52,'东华门街道',4);

INSERT IGNORE INTO category(cat_id,parent_id,cat_name,sort_order) VALUES(1,0,'示例分类',50);
INSERT IGNORE INTO category(cat_id,parent_id,cat_name,sort_order) VALUES(2,1,'C++ 书籍',60);
INSERT IGNORE INTO brand(brand_id,brand_name,sort_order) VALUES(1,'示例品牌',50);
INSERT IGNORE INTO brand(brand_id,brand_name,sort_order) VALUES(2,'C++ Press',60);

INSERT IGNORE INTO article_cat(cat_id,cat_name,cat_desc) VALUES(1,'商城公告','示例文章分类');
INSERT IGNORE INTO article(article_id,cat_id,title,author,article_desc,content,is_open)
 VALUES(1,1,'ECShop Play 项目说明','ecshop-play','用于验证文章 URL 的示例文章','这是来自 article 表的示例正文。',1);

INSERT IGNORE INTO goods(goods_id,cat_id,goods_sn,goods_name,brand_id,goods_number,market_price,shop_price,goods_brief,goods_desc,is_on_sale,is_delete,is_new,is_hot,integral)
 VALUES(12,1,'EC-001','C++ 入门商品',1,18,69.90,49.90,'用于验证第一个 HTTP URL 的示例商品','来自 goods 表',1,0,1,0,10);
INSERT IGNORE INTO goods(goods_id,cat_id,goods_sn,goods_name,brand_id,goods_number,market_price,shop_price,goods_brief,goods_desc,is_on_sale,is_delete,is_new)
 VALUES(14,1,'EC-003','C++ 进阶商品',1,9,99.90,79.90,'用于验证商品对比的第二个公开商品','来自 goods 表',1,0,1);
INSERT IGNORE INTO goods(goods_id,cat_id,goods_sn,goods_name,brand_id,goods_number,market_price,shop_price,goods_brief,goods_desc,is_on_sale,is_delete)
 VALUES(13,1,'EC-002','已下架商品',1,5,129.90,99.90,'不可公开访问','用于验证可见性过滤',0,0);
INSERT IGNORE INTO goods(goods_id,cat_id,goods_sn,goods_name,brand_id,goods_number,market_price,shop_price,promote_price,promote_start_date,promote_end_date,goods_brief,goods_desc,is_on_sale,is_delete,is_promote,is_hot,integral)
 VALUES(15,2,'EC-004','C++ 促销商品',2,3,199.00,159.00,129.00,0,4102444800,'促销与积分示例','来自 goods 表',1,0,1,1,20);

INSERT IGNORE INTO goods_attr(goods_attr_id,goods_id,attr_id,attr_value,attr_price) VALUES(1001,12,1,'扩展版',5.00);
INSERT IGNORE INTO goods_attr(goods_attr_id,goods_id,attr_id,attr_value,attr_price) VALUES(1005,12,2,'标准包装',0.00);
INSERT IGNORE INTO goods_gallery(img_id,goods_id,img_url,img_desc,thumb_url,img_original)
 VALUES(1,12,'https://example.test/images/ecshop-1.jpg','示例商品图','https://example.test/images/ecshop-1-thumb.jpg','https://example.test/images/ecshop-1-original.jpg');
INSERT IGNORE INTO products(product_id,goods_id,product_sn,product_number,product_attr) VALUES(81,12,'EC-001-EXT',10,'1001|1005');
INSERT IGNORE INTO goods_cat(goods_id,cat_id) VALUES(12,2);
INSERT IGNORE INTO tag(tag_id,user_id,goods_id,tag_words) VALUES(1,0,12,'C++');
INSERT IGNORE INTO tag(tag_id,user_id,goods_id,tag_words) VALUES(2,0,12,'商城');
INSERT IGNORE INTO tag(tag_id,user_id,goods_id,tag_words) VALUES(3,0,12,'C++');

INSERT IGNORE INTO shipping(shipping_id,shipping_name,shipping_code,enabled,shipping_fee) VALUES(1,'标准快递','flat',1,8.00);
INSERT IGNORE INTO shipping(shipping_id,shipping_name,shipping_code,enabled,shipping_fee) VALUES(2,'同城速运','ems',1,15.00);
INSERT IGNORE INTO payment(pay_id,pay_name,pay_code,enabled,pay_fee) VALUES(1,'在线支付','alipay',1,0.00);
INSERT IGNORE INTO payment(pay_id,pay_name,pay_code,enabled,pay_fee) VALUES(2,'线下转账','bank',1,2.00);

INSERT IGNORE INTO goods_activity(act_id,act_name,act_desc,act_type,goods_id,goods_name,start_time,end_time,is_finished,ext_info)
 VALUES(1,'示例团购','用于学习 goods_activity 的公开读取',1,12,'C++ 入门商品',0,4102444800,0,'{"cur_price":"39.90","group_count":2,"price_format":"<li>1-2:39.90</li>"}');
INSERT IGNORE INTO goods_activity(act_id,act_name,act_desc,act_type,goods_id,goods_name,start_time,end_time,is_finished,ext_info)
 VALUES(2,'学习礼包','组合购买两个示例商品',4,12,'学习礼包',0,4102444800,0,'{"package_price":"119.00"}');
INSERT IGNORE INTO goods_activity(act_id,act_name,act_desc,act_type,goods_id,goods_name,start_time,end_time,is_finished,ext_info)
 VALUES(3,'积分兑换示例',  'act_type=3 的积分兑换活动',3,15,'C++ 促销商品',0,4102444800,0,'{"points_price":200}');
INSERT IGNORE INTO package_goods(package_id,goods_id,goods_number) VALUES(2,12,1);
INSERT IGNORE INTO package_goods(package_id,goods_id,goods_number) VALUES(2,14,1);

INSERT IGNORE INTO favourable_activity(act_id,act_name,start_time,end_time,user_rank,act_range,act_range_ext,min_amount,max_amount,act_type,act_type_ext,gift,sort_order)
 VALUES(1,'满百减十',0,4102444800,'0',0,'',100.00,0.00,1,10.00,'[]',10);

INSERT IGNORE INTO topic(topic_id,title,intro,start_time,end_time,data,css,topic_img,title_pic,base_style,keywords,description)
 VALUES(1,'C++ 学习专题','按学习阶段组织示例商品',0,4102444800,'{"入门":[12],"进阶":[14]}','.topic-title{color:#336699;}','topic/cpp.jpg','topic/cpp-title.jpg','336699','C++,商城','学习专题示例');

INSERT IGNORE INTO vote(vote_id,vote_name,start_time,end_time,can_multi,vote_count) VALUES(1,'你最想继续学习什么？',0,4102444800,0,0);
INSERT IGNORE INTO vote_option(option_id,vote_id,option_name,option_order) VALUES(1,1,'HTTP 服务',10);
INSERT IGNORE INTO vote_option(option_id,vote_id,option_name,option_order) VALUES(2,1,'数据库事务',20);

INSERT IGNORE INTO exchange_goods(goods_id,exchange_integral,is_exchange,is_hot) VALUES(12,500,1,1);
INSERT IGNORE INTO exchange_goods(goods_id,exchange_integral,is_exchange,is_hot) VALUES(14,800,1,0);

INSERT IGNORE INTO ad(ad_id,position_id,media_type,ad_name,ad_link,ad_code,start_time,end_time,enabled)
 VALUES(1,0,3,'示例广告','https://example.test/ecshop','示例广告文案','1970-01-01 00:00:00','2100-01-01 00:00:00',1);
INSERT IGNORE INTO ad(ad_id,position_id,media_type,ad_name,ad_link,ad_code,start_time,end_time,enabled)
 VALUES(2,1,0,'示例轮播','https://example.test/ecshop','https://example.test/images/banner.jpg','1970-01-01 00:00:00','2100-01-01 00:00:00',1);

INSERT IGNORE INTO bonus_type(type_id,type_name,type_money,use_start_date,use_end_date,min_goods_amount) VALUES(1,'学习红包',10.00,0,4102444800,100.00);
INSERT IGNORE INTO user_bonus(bonus_id,bonus_type_id,bonus_sn) VALUES(1,1,202609230001);

INSERT IGNORE INTO feedback(msg_id,parent_id,user_id,user_name,user_email,msg_title,msg_type,msg_status,msg_content,msg_area)
 VALUES(1,0,0,'示例访客','visitor@example.test','咨询',0,1,'请问何时发货？',1);

INSERT IGNORE INTO points_conversion_option(option_id,option_name,need_points,money,enabled) VALUES(1,'100 积分换 1 元',100,1.00,1);
