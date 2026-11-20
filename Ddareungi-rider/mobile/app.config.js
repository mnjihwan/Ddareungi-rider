function validateReleaseEnvironment() {
  const url = process.env.EXPO_PUBLIC_API_BASE_URL?.trim();
  const message = 'EXPO_PUBLIC_API_BASE_URL에 내부 테스트 또는 운영의 HTTPS 서버 주소를 입력해 주세요.';
  if (!url || !/^https:\/\/[^/?#\\@\s]+\/?$/i.test(url)) throw new Error(message);
  try {
    new URL(url);
  } catch {
    throw new Error(message);
  }
  if (!process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY?.trim()) {
    throw new Error('EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY에 카카오 네이티브 앱 키를 입력해 주세요.');
  }
}

module.exports = ({ config }) => {
  const isRelease = process.env.RIDER_RELEASE_BUILD === 'true'
    || ['preview', 'production'].includes(process.env.EAS_BUILD_PROFILE);
  if (isRelease) validateReleaseEnvironment();

  return {
    ...config,
    ...(isRelease && {
      android: {
        ...config.android,
        blockedPermissions: [
          ...(config.android?.blockedPermissions ?? []),
          'android.permission.READ_EXTERNAL_STORAGE',
          'android.permission.WRITE_EXTERNAL_STORAGE',
          'android.permission.SYSTEM_ALERT_WINDOW',
        ],
      },
    }),
    ios: {
      ...config.ios,
      infoPlist: {
        ...config.ios?.infoPlist,
        NSAppTransportSecurity: {
          NSAllowsArbitraryLoads: false,
          ...(!isRelease && { NSAllowsLocalNetworking: true }),
        },
      },
    },
  };
};
