cd ..

# remove all existing jar files
echo "removing existing jar files"
rm build/libs/*.jar

gradlew build runIde
