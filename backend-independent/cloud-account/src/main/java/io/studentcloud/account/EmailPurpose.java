package io.studentcloud.account;

/** 区分注册验证与密码重置的验证码用途。 Codes are deliberately scoped: a registration code cannot reset an existing account password. */
public enum EmailPurpose { REGISTER, RESET_PASSWORD }
