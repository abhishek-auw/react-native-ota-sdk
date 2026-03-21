/** @type {import('@react-native-community/cli-types').UserDependencyConfig} */
module.exports = {
  dependency: {
    platforms: {
      ios: {
        podspecPath: './OtaSdk.podspec',
      },
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.otasdk.OTASdkPackage;',
        packageInstance: 'new OTASdkPackage()',
      },
    },
  },
};
