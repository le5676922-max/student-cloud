-- Small, platform-owned allowlisted site/view settings. Never store credentials or remote service secrets here.
CREATE TABLE sc_platform_admin_setting (
  setting_key VARCHAR(80) NOT NULL PRIMARY KEY,
  setting_value VARCHAR(4000) NOT NULL
);
