/** Only mount the heavy assistant after authentication; its appearance is unchanged. */
export function shouldMountAssistant(path, loginInfo) {
	return (
		typeof path === 'string' &&
		!['/login', '/install', '/sso'].some((prefix) => path.startsWith(prefix)) &&
		loginInfo?.isLogin === true
	)
}
