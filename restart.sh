VERSION="1.6.15"

echo "Killing any running flockdb..."
curl http://localhost:9990/shutdown >/dev/null 2>/dev/null
sleep 3

JAVA_OPTS="-Xms256m -Xmx256m -XX:NewSize=64m -XX:+UseConcMarkSweepGC -XX:+UseParNewGC -server"
env DB_USERNAME=root DB_PASSWORD=password java $JAVA_OPTS -jar ./dist/flockdb/flockdb-${VERSION}.jar config/development.scala &
echo "Running flockdb..."
