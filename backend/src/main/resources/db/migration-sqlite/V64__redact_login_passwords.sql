-- Retain login events while removing passwords stored by older logging modes.
UPDATE login_log SET password = '******' WHERE password IS NOT NULL AND password <> '******';
UPDATE system_config SET value = 'ignoreAllPwd' WHERE name = 'loginLogMode' AND value IN ('all', 'ignoreSuccessPwd');
