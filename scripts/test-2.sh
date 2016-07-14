#!/bin/bash

if [ "$#" -ne 2 ]; then
  echo "Usage: ./test.sh <IP-Address-MariaDB-Server-1> <IP-Address-MariaDB-Server-2>"
  echo "We will be writing to server-1 and checking if replication is synchronous on server-2" 
  exit 1
fi

SOURCE=$1
REPLICA=$2

###################################################
### Create test table ############################
###################################################
mysql -h $SOURCE -u root -ppass123 -e "CREATE TABLE test.t1 (id INT PRIMARY KEY AUTO_INCREMENT) ENGINE=InnoDB;commit;"
mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);commit;"

###### Set GLOBAL variables #########
mysql -h $SOURCE -u root -ppass123 -e "SET GLOBAL wsrep_gtid_mode = 1;"

mysql -h $REPLICA -u root -ppass123 -e "SET GLOBAL wsrep_gtid_mode = 1;"

count=1
while [ "$count" -le 50000 ]
do
  ##connection node_1: Connect to first node and insert a row
  mysql -h $SOURCE -u root -ppass123 -e "INSERT INTO test.t1 VALUES (DEFAULT);commit; select @@last_gtid;" > /tmp/f3
  mysql -h $SOURCE -u root -ppass123 -e "SELECT MAX(id) FROM test.t1;" > /tmp/f1
  val1=`tail -1 /tmp/f1`
  last_gtid=`tail -1 /tmp/f3`
  echo "$val1"
 
  ##echo "LastGTID: $last_gtid"
 
  ##connection node_2: connect to second node and read from it
  mysql -h $REPLICA -u root -ppass123 -e "SELECT MASTER_GTID_WAIT(\"$last_gtid\", 5), MAX(id) FROM test.t1;" > /tmp/f2
  val2=`cat /tmp/f2 | tail -1 | awk '{print $2}'`
  gtid_wait_response=`cat /tmp/f2 | tail -1 | awk '{print $1}'`

  if [ "$val1" -ne "$val2" ] 
  then
    echo "val1=$val1 val2=$val2"
    echo "syn_wait FAILED" 
    if [ "$gtid_wait_response" -eq "-1" ]; then
	echo "MASTER_GTID_WAIT timed out, read whatever is available from replica"
    fi
    mysql -h $SOURCE -u root -ppass123 -e "DROP TABLE test.t1;"
    exit
  fi
  (( count++ ))
done

##eval SET GLOBAL query_cache_size = $qcache_size_orig
mysql -h $SOURCE -u root -ppass123 -e "DROP TABLE test.t1;"
