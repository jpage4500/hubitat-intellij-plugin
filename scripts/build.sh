cd ..

# remove all existing jar files
echo "removing existing jar files"
rm build/libs/*.jar

gradlew build

cp build/libs/*.jar ~/Downloads
