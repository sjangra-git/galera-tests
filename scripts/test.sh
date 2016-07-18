#!/bin/bash

if [ "$#" -ne 2 ]; then
  echo "Usage: ./test.sh <IP-Address-MariaDB-Server-1> <IP-Address-MariaDB-Server-2>"
  echo "We will be writing to server-1 and checking if replication is synchronous on server-2" 
  exit 1
fi

SOURCE=$1
REPLICA=$2
## Value of wsrep_sync_wait variable
WSREP_SYNC_VAL=1

###################################################
### Create test table ############################
###################################################
mysql -h $SOURCE -u root -ppass123 -e "CREATE TABLE test.t1 (id INT PRIMARY KEY AUTO_INCREMENT) ENGINE=InnoDB;"
mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);"

##let $qcache_size_orig = `SELECT @@GLOBAL.query_cache_size`
mysql -h $SOURCE -u root -ppass123 -e "SET GLOBAL query_cache_size=1355776;"

###### Set wsrep_sync_wait & autocommit variable at GLOBAL level #########
mysql -h $SOURCE -u root -ppass123 -e "SET GLOBAL wsrep_sync_wait = $WSREP_SYNC_VAL, autocommit=ON;"
mysql -h $REPLICA -u root -ppass123 -e "SET GLOBAL wsrep_sync_wait = $WSREP_SYNC_VAL, autocommit=ON;"


count=1
while [ "$count" -le 50000 ]
do
  ##connection node_1: Connect to first node and insert a row
  mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);"
  mysql -h $SOURCE -u root -ppass123 -e "SELECT MAX(id) FROM test.t1;" > /tmp/f1
  val1=`tail -1 /tmp/f1`

  ##connection node_2: connect to second node and read from it
  mysql -h $REPLICA -u root -ppass123 -e "SELECT MAX(id) FROM test.t1;" > /tmp/f2
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
