"""使用官方 Android SDK 和 JDK 17 构建，无 Gradle 或应用后端。"""
from pathlib import Path
import argparse, os, subprocess, zipfile, shutil

root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--sdk',required=True,help='包含 android-35 和 build-tools 的 SDK 路径')
parser.add_argument('--work',default=str(root.parent.parent/'work'/'life-lite-build'))
parser.add_argument('--output',default=str(root.parent/'life-lite-0.5.3.apk'))
args=parser.parse_args()
sdk=Path(args.sdk).resolve();work=Path(args.work).resolve();work.mkdir(parents=True,exist_ok=True)
platform=next(sdk.rglob('android.jar'))
aapt=next(sdk.rglob('aapt2.exe'));bt=aapt.parent
javac=shutil.which('javac')
if not javac:
    java=shutil.which('java');javac=str(Path(java).with_name('javac.exe'))
java=str(Path(javac).with_name('java.exe'));keytool=str(Path(javac).with_name('keytool.exe'));jar=str(Path(javac).with_name('jar.exe'))
zxing=root/'libs'/'zxing-core-3.5.3.jar'
alipay=root/'libs'/'alipaysdk-15.8.35.jar'
def run(*command):
    command=[str(x) for x in command]
    if Path(command[0]).stem.lower()=='javac':command.insert(1,'-J-Duser.language=en')
    subprocess.run(command,check=True,cwd=root)
compiled=work/'resources.zip';unsigned=work/'unsigned.apk';aligned=work/'aligned.apk'
gen=work/'gen';gen.mkdir(exist_ok=True)
classes=work/'classes';classes.mkdir(exist_ok=True)
dex=work/'dex';dex.mkdir(exist_ok=True)
run(aapt,'compile','--dir',root/'res','-o',compiled)
run(aapt,'link','-o',unsigned,'-I',platform,'--manifest',root/'AndroidManifest.xml','-R',compiled,'-A',root/'assets','--java',gen,'--extra-packages','com.alipay.sdk','--auto-add-overlay')
sources=sorted((root/'src').rglob('*.java'))+sorted(gen.rglob('*.java'))
run(javac,'-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(platform)+os.pathsep+str(bt/'core-lambda-stubs.jar'),'-cp',str(zxing)+os.pathsep+str(alipay),'-d',classes,*sources)
jarfile=work/'classes.jar'
with zipfile.ZipFile(jarfile,'w',compression=zipfile.ZIP_DEFLATED) as archive:
    for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
run(java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',platform,'--output',dex,jarfile,zxing,alipay)
with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as apk:
    for path in sorted(dex.glob('classes*.dex')):apk.write(path,path.name)
run(bt/'zipalign.exe','-f','4',unsigned,aligned)
signing=work/'signing';signing.mkdir(exist_ok=True)
keystore=signing/'local-test.p12'
if not keystore.exists():
    run(keytool,'-genkeypair','-keystore',keystore,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','lifelite-local','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Life Lite Local Test')
out=Path(args.output).resolve();out.parent.mkdir(parents=True,exist_ok=True)
run(java,'-jar',bt/'lib/apksigner.jar','sign','--ks',keystore,'--ks-pass','pass:android','--ks-key-alias','lifelite-local','--out',out,aligned)
run(java,'-jar',bt/'lib/apksigner.jar','verify','--verbose',out)
print('APK:',out,'bytes:',out.stat().st_size)
