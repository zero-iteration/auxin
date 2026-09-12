set -e
cd /w/linux/j8
echo "classfile major: $(od -An -tu1 -j6 -N2 T.class | awk '{print $1*256+$2}')  (52 = Java 8)"
java -version 2>&1 | head -1
echo "--- JDK 8 attach: field+<clinit> fallback path (classfile < 55, no condy) ---"
java -javaagent:/w/modules/ax-agent/target/ax-agent.jar \
     -Dax.include.packages=T -Dax.environment=production \
     -Dax.manifest=/w/linux/j8/manifest.json -Dax.debug=true \
     -cp . T 2>&1 | tail -25
