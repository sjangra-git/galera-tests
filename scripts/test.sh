#!/bin/bash

SOURCE=$1
REPLICA=$2
WSREP_SYNC_VAL=1

mysql -h $SOURCE -u root -ppass123 -e "CREATE TABLE test.t1 (id INT PRIMARY KEY AUTO_INCREMENT) ENGINE=InnoDB;commit;"
mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);commit;"

#let $qcache_size_orig = `SELECT @@GLOBAL.query_cache_size`
#mysql -u root -ppass123 -e "SET GLOBAL query_cache_size=1355776;"
mysql -h $SOURCE -u root -ppass123 -e "SET GLOBAL wsrep_sync_wait = $WSREP_SYNC_VAL;"
mysql -h $REPLICA -u root -ppass123 -e "SET GLOBAL wsrep_sync_wait = $WSREP_SYNC_VAL;"


count=1
while [ "$count" -le 5000 ]
do
  ##connection node_1
  mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);commit;"
  mysql -h $SOURCE -u root -ppass123 -e "SET SESSION wsrep_sync_wait = $WSREP_SYNC_VAL; SELECT MAX(id) FROM test.t1;" > /tmp/f1
  val1=`tail -1 /tmp/f1`

  ##connection node_2
  mysql -h $REPLICA -u root -ppass123 -e "SET SESSION wsrep_sync_wait = $WSREP_SYNC_VAL; SELECT MAX(id) FROM test.t1;" > /tmp/f2
  val2=`tail -1 /tmp/f2`

  if [ "$val1" -ne "$val2" ] 
  then
    echo "val1=$val1 val2=$val2"
    echo "syn_wait FAILED" 
    mysql -h $SOURCE -u root -ppass123 -e "DROP TABLE test.t1;"
    exit
  fi
  (( count++ ))
done

##eval SET GLOBAL query_cache_size = $qcache_size_orig
mysql -h $SOURCE -u root -ppass123 -e "DROP TABLE test.t1;"
