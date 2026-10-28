package io.studentcloud.account;

/** Codes are deliberately scoped: a registration code cannot reset an existing account password. */
public enum EmailPurpose { REGISTER, RESET_PASSWORD }
