UPDATE system_config
SET value = ''
WHERE name = 'onlyOfficeUrl'
  AND value IN (
    CONCAT('http://office.', 'z', 'file.vip'),
    CONCAT('https://office.', 'z', 'file.vip')
  );
