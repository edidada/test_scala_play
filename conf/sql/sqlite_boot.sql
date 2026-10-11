-- SQLite bootstrap schema for the Scala Play ECShop API.
-- Table and column names mirror docs/sql/001_core_schema.mysql8.sql exactly so
-- that repository SQL is shared between SQLite and MySQL. Executed once at
-- application start; every statement is idempotent (IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS region (
  region_id INTEGER PRIMARY KEY,
  parent_id INTEGER NOT NULL DEFAULT 0,
  region_name VARCHAR(120) NOT NULL,
  region_type INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_region_parent ON region(parent_id);

CREATE TABLE IF NOT EXISTS users (
  user_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_name VARCHAR(60) NOT NULL UNIQUE,
  email VARCHAR(120) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  mobile_phone VARCHAR(32) NOT NULL DEFAULT '',
  user_money DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  pay_points INTEGER NOT NULL DEFAULT 0,
  rank_points INTEGER NOT NULL DEFAULT 0,
  is_validated INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS user_referral (
  user_id INTEGER PRIMARY KEY,
  referrer_user_id INTEGER NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(user_id <> referrer_user_id),
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  FOREIGN KEY(referrer_user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_referral_referrer ON user_referral(referrer_user_id,user_id);

CREATE TABLE IF NOT EXISTS account_balance (
  user_id INTEGER PRIMARY KEY,
  available_cents INTEGER NOT NULL DEFAULT 0 CHECK(available_cents >= 0),
  frozen_cents INTEGER NOT NULL DEFAULT 0 CHECK(frozen_cents >= 0),
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_account (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
  process_type VARCHAR(20) NOT NULL CHECK(process_type IN ('deposit','withdrawal')),
  payment_id INTEGER,
  user_note VARCHAR(255) NOT NULL DEFAULT '',
  admin_note VARCHAR(255) NOT NULL DEFAULT '',
  status VARCHAR(30) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at DATETIME,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  FOREIGN KEY(payment_id) REFERENCES payment(pay_id)
);
CREATE INDEX IF NOT EXISTS idx_user_account_user ON user_account(user_id,rec_id);

CREATE TABLE IF NOT EXISTS account_payment_intent (
  intent_id INTEGER PRIMARY KEY AUTOINCREMENT,
  request_id INTEGER NOT NULL UNIQUE,
  user_id INTEGER NOT NULL,
  payment_id INTEGER NOT NULL,
  amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
  fee_cents INTEGER NOT NULL DEFAULT 0 CHECK(fee_cents >= 0),
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(request_id) REFERENCES user_account(rec_id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  FOREIGN KEY(payment_id) REFERENCES payment(pay_id)
);
CREATE INDEX IF NOT EXISTS idx_account_payment_intent_user ON account_payment_intent(user_id,intent_id);

CREATE TABLE IF NOT EXISTS account_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  available_delta_cents INTEGER NOT NULL DEFAULT 0,
  frozen_delta_cents INTEGER NOT NULL DEFAULT 0,
  reason VARCHAR(255) NOT NULL DEFAULT '',
  reference_type VARCHAR(60) NOT NULL DEFAULT '',
  reference_id INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_account_log_user ON account_log(user_id,log_id);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
  user_id INTEGER PRIMARY KEY,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  consumed_at DATETIME,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS email_verified_users (
  user_id INTEGER PRIMARY KEY,
  verified_at DATETIME NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
  user_id INTEGER PRIMARY KEY,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  consumed_at DATETIME,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS email_outbox (
  message_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  recipient VARCHAR(120) NOT NULL,
  template_name VARCHAR(60) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at DATETIME,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_email_outbox_pending ON email_outbox(status,message_id);

CREATE TABLE IF NOT EXISTS email_list (
  email VARCHAR(120) PRIMARY KEY,
  status INTEGER NOT NULL DEFAULT 0 CHECK(status IN (0,1)),
  token_hash CHAR(64) UNIQUE,
  pending_action VARCHAR(20) NOT NULL DEFAULT '' CHECK(pending_action IN ('','subscribe','unsubscribe')),
  token_expires_at DATETIME,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bonus_type (
  type_id INTEGER PRIMARY KEY AUTOINCREMENT,
  type_name VARCHAR(60) NOT NULL DEFAULT '',
  type_money DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  send_type INTEGER NOT NULL DEFAULT 0,
  min_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  max_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  send_start_date INTEGER NOT NULL DEFAULT 0,
  send_end_date INTEGER NOT NULL DEFAULT 0,
  use_start_date INTEGER NOT NULL DEFAULT 0,
  use_end_date INTEGER NOT NULL DEFAULT 0,
  min_goods_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
);

CREATE TABLE IF NOT EXISTS user_bonus (
  bonus_id INTEGER PRIMARY KEY AUTOINCREMENT,
  bonus_type_id INTEGER NOT NULL DEFAULT 0,
  bonus_sn INTEGER NOT NULL UNIQUE,
  user_id INTEGER NOT NULL DEFAULT 0,
  used_time INTEGER NOT NULL DEFAULT 0,
  order_id INTEGER NOT NULL DEFAULT 0,
  emailed INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(bonus_type_id) REFERENCES bonus_type(type_id)
);
CREATE INDEX IF NOT EXISTS idx_user_bonus_user ON user_bonus(user_id);

CREATE TABLE IF NOT EXISTS user_address (
  address_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  consignee VARCHAR(60) NOT NULL,
  country_id INTEGER NOT NULL DEFAULT 0,
  province_id INTEGER NOT NULL DEFAULT 0,
  city_id INTEGER NOT NULL DEFAULT 0,
  district_id INTEGER NOT NULL DEFAULT 0,
  address VARCHAR(255) NOT NULL,
  zipcode VARCHAR(20) NOT NULL DEFAULT '',
  mobile VARCHAR(32) NOT NULL DEFAULT '',
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_address_user ON user_address(user_id);

CREATE TABLE IF NOT EXISTS category (
  cat_id INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id INTEGER NOT NULL DEFAULT 0,
  cat_name VARCHAR(120) NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 50,
  is_show INTEGER NOT NULL DEFAULT 1,
  keywords VARCHAR(255) NOT NULL DEFAULT '',
  cat_desc VARCHAR(255) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_category_parent_sort ON category(parent_id,sort_order);

CREATE TABLE IF NOT EXISTS brand (
  brand_id INTEGER PRIMARY KEY AUTOINCREMENT,
  brand_name VARCHAR(120) NOT NULL,
  brand_logo VARCHAR(255) NOT NULL DEFAULT '',
  site_url VARCHAR(255) NOT NULL DEFAULT '',
  is_show INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL DEFAULT 50,
  UNIQUE(brand_name)
);

CREATE TABLE IF NOT EXISTS article_cat (
  cat_id INTEGER PRIMARY KEY AUTOINCREMENT,
  cat_name VARCHAR(120) NOT NULL,
  cat_desc TEXT NOT NULL DEFAULT '',
  keywords VARCHAR(255) NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL DEFAULT 50,
  is_show INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS article (
  article_id INTEGER PRIMARY KEY AUTOINCREMENT,
  cat_id INTEGER NOT NULL,
  title VARCHAR(255) NOT NULL,
  author VARCHAR(255) NOT NULL DEFAULT '',
  article_desc TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  keywords VARCHAR(255) NOT NULL DEFAULT '',
  is_open INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY(cat_id) REFERENCES article_cat(cat_id)
);
CREATE INDEX IF NOT EXISTS idx_article_category_open ON article(cat_id,is_open,article_id);

CREATE TABLE IF NOT EXISTS goods (
  goods_id INTEGER PRIMARY KEY AUTOINCREMENT,
  cat_id INTEGER NOT NULL,
  brand_id INTEGER NOT NULL DEFAULT 0,
  goods_sn VARCHAR(80) NOT NULL DEFAULT '',
  goods_name VARCHAR(255) NOT NULL,
  goods_brief VARCHAR(255) NOT NULL DEFAULT '',
  keywords VARCHAR(255) NOT NULL DEFAULT '',
  goods_desc TEXT NOT NULL DEFAULT '',
  goods_thumb VARCHAR(255) NOT NULL DEFAULT '',
  goods_img VARCHAR(255) NOT NULL DEFAULT '',
  original_img VARCHAR(255) NOT NULL DEFAULT '',
  shop_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  market_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  promote_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  promote_start_date INTEGER NOT NULL DEFAULT 0,
  promote_end_date INTEGER NOT NULL DEFAULT 0,
  goods_number INTEGER NOT NULL DEFAULT 0,
  goods_weight DECIMAL(10,3) NOT NULL DEFAULT 0.000,
  integral INTEGER NOT NULL DEFAULT 0,
  is_on_sale INTEGER NOT NULL DEFAULT 1,
  is_alone_sale INTEGER NOT NULL DEFAULT 1,
  is_shipping INTEGER NOT NULL DEFAULT 0,
  is_real INTEGER NOT NULL DEFAULT 1,
  is_delete INTEGER NOT NULL DEFAULT 0,
  is_best INTEGER NOT NULL DEFAULT 0,
  is_new INTEGER NOT NULL DEFAULT 0,
  is_hot INTEGER NOT NULL DEFAULT 0,
  is_promote INTEGER NOT NULL DEFAULT 0,
  click_count INTEGER NOT NULL DEFAULT 0,
  add_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_update DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(goods_sn),
  FOREIGN KEY(cat_id) REFERENCES category(cat_id),
  FOREIGN KEY(brand_id) REFERENCES brand(brand_id)
);
CREATE INDEX IF NOT EXISTS idx_goods_listing ON goods(cat_id,is_on_sale,is_delete);
CREATE INDEX IF NOT EXISTS idx_goods_brand ON goods(brand_id);

CREATE TABLE IF NOT EXISTS products (
  product_id INTEGER PRIMARY KEY AUTOINCREMENT,
  goods_id INTEGER NOT NULL,
  product_sn VARCHAR(80) NOT NULL DEFAULT '',
  product_number INTEGER NOT NULL DEFAULT 0,
  product_attr TEXT NOT NULL DEFAULT '',
  UNIQUE(product_sn),
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_product_goods ON products(goods_id);

CREATE TABLE IF NOT EXISTS goods_attr (
  goods_attr_id INTEGER PRIMARY KEY AUTOINCREMENT,
  goods_id INTEGER NOT NULL,
  attr_id INTEGER NOT NULL DEFAULT 0,
  attr_value TEXT NOT NULL DEFAULT '',
  attr_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_goods_attr_goods ON goods_attr(goods_id);

CREATE TABLE IF NOT EXISTS goods_gallery (
  img_id INTEGER PRIMARY KEY AUTOINCREMENT,
  goods_id INTEGER NOT NULL,
  img_url VARCHAR(255) NOT NULL,
  img_desc VARCHAR(255) NOT NULL DEFAULT '',
  thumb_url VARCHAR(255) NOT NULL DEFAULT '',
  img_original VARCHAR(255) NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_gallery_goods ON goods_gallery(goods_id);

CREATE TABLE IF NOT EXISTS goods_cat (
  goods_id INTEGER NOT NULL,
  cat_id INTEGER NOT NULL,
  PRIMARY KEY(goods_id,cat_id)
);

CREATE TABLE IF NOT EXISTS tag (
  tag_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL DEFAULT 0,
  goods_id INTEGER NOT NULL,
  tag_words VARCHAR(255) NOT NULL DEFAULT '',
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_tag_words ON tag(tag_words);
CREATE INDEX IF NOT EXISTS idx_tag_goods ON tag(goods_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tag_authenticated_user_goods_word ON tag(user_id,goods_id,tag_words) WHERE user_id <> 0;

CREATE TABLE IF NOT EXISTS booking_goods (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL DEFAULT 0,
  email VARCHAR(60) NOT NULL DEFAULT '',
  link_man VARCHAR(60) NOT NULL DEFAULT '',
  tel VARCHAR(60) NOT NULL DEFAULT '',
  goods_id INTEGER NOT NULL DEFAULT 0,
  goods_desc VARCHAR(255) NOT NULL DEFAULT '',
  goods_number INTEGER NOT NULL DEFAULT 0,
  booking_time INTEGER NOT NULL DEFAULT 0,
  is_dispose INTEGER NOT NULL DEFAULT 0,
  dispose_user VARCHAR(30) NOT NULL DEFAULT '',
  dispose_time INTEGER NOT NULL DEFAULT 0,
  dispose_note VARCHAR(255) NOT NULL DEFAULT '',
  UNIQUE(user_id,goods_id)
);
CREATE INDEX IF NOT EXISTS idx_booking_user ON booking_goods(user_id);

CREATE TABLE IF NOT EXISTS cart (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  goods_id INTEGER NOT NULL,
  product_id INTEGER,
  attr_signature CHAR(64) NOT NULL DEFAULT '',
  goods_attr TEXT NOT NULL DEFAULT '',
  goods_number INTEGER NOT NULL CHECK(goods_number > 0),
  version INTEGER NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id,goods_id,attr_signature),
  FOREIGN KEY(user_id) REFERENCES users(user_id),
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_cart_user ON cart(user_id);

CREATE TABLE IF NOT EXISTS payment (
  pay_id INTEGER PRIMARY KEY AUTOINCREMENT,
  pay_name VARCHAR(120) NOT NULL,
  pay_code VARCHAR(60) NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  pay_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  UNIQUE(pay_code)
);

CREATE TABLE IF NOT EXISTS shipping (
  shipping_id INTEGER PRIMARY KEY AUTOINCREMENT,
  shipping_name VARCHAR(120) NOT NULL,
  shipping_code VARCHAR(60) NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  UNIQUE(shipping_code)
);

CREATE TABLE IF NOT EXISTS order_info (
  order_id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_sn VARCHAR(40) NOT NULL UNIQUE,
  user_id INTEGER NOT NULL,
  order_status VARCHAR(32) NOT NULL DEFAULT 'pending_payment',
  shipping_status VARCHAR(32) NOT NULL DEFAULT 'unshipped',
  pay_status VARCHAR(32) NOT NULL DEFAULT 'unpaid',
  consignee VARCHAR(60) NOT NULL,
  email VARCHAR(60) NOT NULL DEFAULT '',
  address VARCHAR(255) NOT NULL,
  mobile VARCHAR(32) NOT NULL DEFAULT '',
  shipping_id INTEGER,
  pay_id INTEGER,
  goods_amount DECIMAL(12,2) NOT NULL,
  shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  payment_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  order_amount DECIMAL(12,2) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_fingerprint TEXT NOT NULL,
  remark VARCHAR(255) NOT NULL DEFAULT '',
  add_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time DATETIME,
  shipping_time DATETIME,
  UNIQUE(user_id,idempotency_key),
  FOREIGN KEY(user_id) REFERENCES users(user_id),
  FOREIGN KEY(shipping_id) REFERENCES shipping(shipping_id),
  FOREIGN KEY(pay_id) REFERENCES payment(pay_id)
);
CREATE INDEX IF NOT EXISTS idx_order_user_time ON order_info(user_id,add_time);

CREATE TABLE IF NOT EXISTS order_delivery_address (
  order_id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL,
  email VARCHAR(60) NOT NULL,
  zipcode VARCHAR(60) NOT NULL DEFAULT '',
  telephone VARCHAR(60) NOT NULL DEFAULT '',
  sign_building VARCHAR(120) NOT NULL DEFAULT '',
  best_time VARCHAR(120) NOT NULL DEFAULT '',
  FOREIGN KEY(order_id) REFERENCES order_info(order_id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_order_delivery_address_user ON order_delivery_address(user_id,order_id);

CREATE TABLE IF NOT EXISTS order_balance_payment (
  order_id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL,
  paid_cents INTEGER NOT NULL DEFAULT 0 CHECK(paid_cents >= 0),
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(order_id) REFERENCES order_info(order_id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_order_balance_payment_user ON order_balance_payment(user_id,order_id);

CREATE TABLE IF NOT EXISTS order_goods (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  goods_id INTEGER NOT NULL,
  product_id INTEGER,
  goods_name VARCHAR(255) NOT NULL,
  goods_sn VARCHAR(80) NOT NULL DEFAULT '',
  goods_number INTEGER NOT NULL CHECK(goods_number > 0),
  market_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  goods_price DECIMAL(12,2) NOT NULL,
  goods_attr TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(order_id) REFERENCES order_info(order_id)
);
CREATE INDEX IF NOT EXISTS idx_order_goods_order ON order_goods(order_id);

CREATE TABLE IF NOT EXISTS order_action (
  action_id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor_id INTEGER,
  action_note VARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(order_id) REFERENCES order_info(order_id)
);
CREATE INDEX IF NOT EXISTS idx_order_action_order ON order_action(order_id);

CREATE TABLE IF NOT EXISTS affiliate_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  user_name VARCHAR(60) NOT NULL DEFAULT '',
  money DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  point INTEGER NOT NULL DEFAULT 0,
  separate_type INTEGER NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(order_id,user_id),
  FOREIGN KEY(order_id) REFERENCES order_info(order_id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_affiliate_log_user ON affiliate_log(user_id,log_id);

CREATE TABLE IF NOT EXISTS pay_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id INTEGER NOT NULL,
  provider VARCHAR(60) NOT NULL,
  provider_trade_no VARCHAR(120) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  raw_payload TEXT NOT NULL DEFAULT '',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(provider,provider_trade_no),
  FOREIGN KEY(order_id) REFERENCES order_info(order_id)
);

CREATE TABLE IF NOT EXISTS comment (
  comment_id INTEGER PRIMARY KEY AUTOINCREMENT,
  comment_type INTEGER NOT NULL DEFAULT 0,
  id_value INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  user_name VARCHAR(60) NOT NULL,
  content TEXT NOT NULL,
  status INTEGER NOT NULL DEFAULT 1,
  add_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  parent_id INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(user_id) REFERENCES users(user_id)
);
CREATE INDEX IF NOT EXISTS idx_comment_target_status ON comment(id_value,comment_type,status,comment_id);

CREATE TABLE IF NOT EXISTS goods_activity (
  act_id INTEGER PRIMARY KEY AUTOINCREMENT,
  act_name VARCHAR(255) NOT NULL,
  act_desc TEXT NOT NULL DEFAULT '',
  act_type INTEGER NOT NULL,
  goods_id INTEGER NOT NULL,
  product_id INTEGER NOT NULL DEFAULT 0,
  goods_name VARCHAR(255) NOT NULL DEFAULT '',
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  is_finished INTEGER NOT NULL DEFAULT 0,
  ext_info TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS idx_activity_active ON goods_activity(act_type,start_time,end_time,is_finished);

CREATE TABLE IF NOT EXISTS order_promotion (
  order_id INTEGER PRIMARY KEY,
  promotion_id INTEGER NOT NULL,
  promotion_type VARCHAR(32) NOT NULL,
  FOREIGN KEY(order_id) REFERENCES order_info(order_id) ON DELETE CASCADE,
  FOREIGN KEY(promotion_id) REFERENCES goods_activity(act_id)
);
CREATE INDEX IF NOT EXISTS idx_order_promotion_activity ON order_promotion(promotion_type,promotion_id,order_id);

CREATE TABLE IF NOT EXISTS package_goods (
  package_id INTEGER NOT NULL,
  goods_id INTEGER NOT NULL,
  product_id INTEGER NOT NULL DEFAULT 0,
  goods_number INTEGER NOT NULL DEFAULT 1,
  admin_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(package_id,goods_id,admin_id,product_id),
  FOREIGN KEY(package_id) REFERENCES goods_activity(act_id),
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);

CREATE TABLE IF NOT EXISTS favourable_activity (
  act_id INTEGER PRIMARY KEY AUTOINCREMENT,
  act_name VARCHAR(255) NOT NULL,
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  user_rank VARCHAR(255) NOT NULL DEFAULT '0',
  act_range INTEGER NOT NULL DEFAULT 0,
  act_range_ext VARCHAR(255) NOT NULL DEFAULT '',
  min_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  max_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
  act_type INTEGER NOT NULL DEFAULT 0,
  act_type_ext DECIMAL(10,2) NOT NULL DEFAULT 0,
  gift TEXT NOT NULL DEFAULT '[]',
  sort_order INTEGER NOT NULL DEFAULT 50
);
CREATE INDEX IF NOT EXISTS ix_favourable_activity_order ON favourable_activity(sort_order,end_time);

CREATE TABLE IF NOT EXISTS topic (
  topic_id INTEGER PRIMARY KEY AUTOINCREMENT,
  title VARCHAR(255) NOT NULL,
  intro TEXT NOT NULL DEFAULT '',
  start_time INTEGER NOT NULL DEFAULT 0,
  end_time INTEGER NOT NULL DEFAULT 0,
  data TEXT NOT NULL DEFAULT '{}',
  template VARCHAR(255) NOT NULL DEFAULT '',
  css TEXT NOT NULL DEFAULT '',
  topic_img VARCHAR(255),
  title_pic VARCHAR(255),
  base_style VARCHAR(6),
  htmls TEXT,
  keywords VARCHAR(255),
  description VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS ix_topic_active ON topic(start_time,end_time);

CREATE TABLE IF NOT EXISTS vote (
  vote_id INTEGER PRIMARY KEY AUTOINCREMENT,
  vote_name VARCHAR(250) NOT NULL DEFAULT '',
  start_time INTEGER NOT NULL DEFAULT 0,
  end_time INTEGER NOT NULL DEFAULT 0,
  can_multi INTEGER NOT NULL DEFAULT 0,
  vote_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_vote_active ON vote(start_time,end_time);

CREATE TABLE IF NOT EXISTS vote_option (
  option_id INTEGER PRIMARY KEY AUTOINCREMENT,
  vote_id INTEGER NOT NULL,
  option_name VARCHAR(250) NOT NULL DEFAULT '',
  option_count INTEGER NOT NULL DEFAULT 0,
  option_order INTEGER NOT NULL DEFAULT 100,
  FOREIGN KEY(vote_id) REFERENCES vote(vote_id)
);
CREATE INDEX IF NOT EXISTS ix_vote_option ON vote_option(vote_id,option_order);

CREATE TABLE IF NOT EXISTS vote_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  vote_id INTEGER NOT NULL,
  ip_address VARCHAR(45) NOT NULL,
  vote_time INTEGER NOT NULL DEFAULT 0,
  UNIQUE(vote_id,ip_address),
  FOREIGN KEY(vote_id) REFERENCES vote(vote_id)
);

CREATE TABLE IF NOT EXISTS exchange_goods (
  goods_id INTEGER PRIMARY KEY,
  exchange_integral INTEGER NOT NULL DEFAULT 0,
  is_exchange INTEGER NOT NULL DEFAULT 0,
  is_hot INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);
CREATE INDEX IF NOT EXISTS ix_exchange_list ON exchange_goods(is_exchange,exchange_integral);

CREATE TABLE IF NOT EXISTS collect_goods (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  goods_id INTEGER NOT NULL,
  add_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_attention INTEGER NOT NULL DEFAULT 0,
  UNIQUE(user_id,goods_id),
  FOREIGN KEY(user_id) REFERENCES users(user_id),
  FOREIGN KEY(goods_id) REFERENCES goods(goods_id)
);

CREATE TABLE IF NOT EXISTS feedback (
  msg_id INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id INTEGER NOT NULL DEFAULT 0,
  user_id INTEGER NOT NULL DEFAULT 0,
  user_name VARCHAR(60) NOT NULL DEFAULT '',
  user_email VARCHAR(60) NOT NULL DEFAULT '',
  msg_title VARCHAR(200) NOT NULL DEFAULT '',
  msg_type INTEGER NOT NULL DEFAULT 0,
  msg_status INTEGER NOT NULL DEFAULT 1,
  msg_content TEXT NOT NULL,
  msg_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  message_img VARCHAR(255) NOT NULL DEFAULT '0',
  order_id INTEGER NOT NULL DEFAULT 0,
  msg_area INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS ix_feedback_board ON feedback(msg_area,msg_status,msg_time);
CREATE INDEX IF NOT EXISTS ix_feedback_user ON feedback(user_id);

CREATE TABLE IF NOT EXISTS ad (
  ad_id INTEGER PRIMARY KEY AUTOINCREMENT,
  position_id INTEGER NOT NULL DEFAULT 0,
  media_type INTEGER NOT NULL DEFAULT 0,
  ad_name VARCHAR(60) NOT NULL DEFAULT '',
  ad_link VARCHAR(255) NOT NULL DEFAULT '',
  ad_code TEXT NOT NULL DEFAULT '',
  start_time DATETIME NOT NULL DEFAULT '1970-01-01 00:00:00',
  end_time DATETIME NOT NULL DEFAULT '2100-01-01 00:00:00',
  click_count INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS ix_ad_active ON ad(enabled,start_time,end_time);

CREATE TABLE IF NOT EXISTS adsense (
  from_ad INTEGER NOT NULL,
  referer VARCHAR(255) NOT NULL DEFAULT '',
  clicks INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(from_ad,referer)
);

CREATE TABLE IF NOT EXISTS browsing_history (
  rec_id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  goods_id INTEGER NOT NULL,
  view_time INTEGER NOT NULL DEFAULT 0,
  ip_address VARCHAR(45) NOT NULL DEFAULT '',
  query_string VARCHAR(255) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_browsing_history_user ON browsing_history(user_id,view_time);

CREATE TABLE IF NOT EXISTS points_conversion_option (
  option_id INTEGER PRIMARY KEY AUTOINCREMENT,
  option_name VARCHAR(120) NOT NULL,
  need_points INTEGER NOT NULL,
  money DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  enabled INTEGER NOT NULL DEFAULT 1
);
