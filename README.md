# galera-tests

How to build?
----------------------------------------------
1. git clone https://github.com/sjangra/galera-tests.git
2. cd galera-tests/galera
3. mvn clean package

How to run?
----------------------------------------------

1. scp target/galera-0.0.1-SNAPSHOT-jar-with-dependencies.jar TO_YOUR_SERVER
2. java -jar galera-0.0.1-SNAPSHOT-jar-with-dependencies.jar --pass ROOT_USER_PASSWORD --db DATABASE_NAME --nodes SERVER_IP1,SERVER_IP2,SERVER_IP3  --sw 1

Create the following test table on your galera cluster. This test assumes that the schema name is 'test' :

1. use test;
2. create table snc (pk int(11), val int(11), ts timestamp(6), PRIMARY KEY (pk));
3. insert into snc values (1, 0, NOW());
