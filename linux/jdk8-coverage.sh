set -e
cd /w/linux/j8
javac -d . Collector.java
java -cp . Collector > /tmp/collector.log 2>&1 &
sleep 1
cat > Loop.java <<'J'
public class Loop { public static void main(String[] a) throws Exception {
  T t = new T(); t.alpha(50); t.gamma("hi"); Thread.sleep(5000); System.out.println("APP_OK a="+t.alpha(5)); } }
J
javac -cp . -d . Loop.java
java -javaagent:/w/modules/ax-agent/target/ax-agent.jar \
     -Dax.include.packages=T -Dax.environment=production \
     -Dax.manifest=/w/linux/j8/manifest.json \
     -Dax.collector.url=http://127.0.0.1:9999 -Dax.flush.interval.ms=2000 \
     -cp . Loop 2>&1 | tail -3
sleep 2
echo "--- collector received ---"
cat /tmp/collector.log | head -30
