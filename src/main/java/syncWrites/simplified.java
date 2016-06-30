package syncWrites;

/**
 * Check Galera discrepancies in sync writes (parameter wsrep_sync_wait)
 *
 * Run:
 * scp Java/bin/syncWrite/* app@10.9.202.189:syncWrite/Java/syncWrite; ssh app@10.9.202.189 '(cd syncWrite/Java; java -cp ".:mysql-connector-java-5.1.39-bin.jar" syncWrite.simplified --pass *** --db test --nodes 10.9.202.189,100.64.6.114,10.9.197.46 --sw 7)'
 */


import java.text.SimpleDateFormat;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class simplified {
    static class Cfg {
        String   user = "root";
        String   pass;
        String   dbName;
        String[] nodes;
        int      delay = 0;
        String   wsrep_sync_wait = "0";
        String   pk = "1";
    }

    static class NodeState {
        String     addr;
        Connection conn;
        int        maxTsDiff  =  0;
        int        maxValDiff = -1;  // negative, to print stats on the first cycle
        NodeState(String addr) { this.addr = addr; }
		@Override
		public String toString() {
			return "NodeState [addr=" + addr + ", conn=" + conn
					+ ", maxTsDiff=" + maxTsDiff + ", maxValDiff=" + maxValDiff
					+ "]";
		}


    }

    static String tsPrefix() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss: ").format(new Date()); }
    static void printlnRaw(String s)                 { System.out.println(s); }
    static void printfRaw(String fmt, Object...args) { System.out.printf(fmt, args); }
    static void println(String s)                    { System.out.print(tsPrefix()); System.out.println(s); }
    static void printf(String fmt, Object...args)    { System.out.printf(fmt, args); }

    static Cfg cfg;
    static NodeState[] nodeStates;
    static volatile int latestVal = -1;
    static NodeState sourceNode = null;

    public static void main(String[] args) throws SQLException, InterruptedException {

    	System.out.println("++ Single Threaded ++");
        simplified.cfg = parseArgs(args);
        if( simplified.cfg == null )
            return;

        simplified.nodeStates = new NodeState[cfg.nodes.length];
        for(int k=0; k < cfg.nodes.length; k++)
            nodeStates[k] = new NodeState(cfg.nodes[k]);

        sourceNode = nodeStates[0];

        System.out.println("Source Node: " + sourceNode.toString());

		while (true) {
			increment(nodeStates[0], cfg.pk);

	        for(int i = 1; i<3; i++) {
	        	queryAndReport(nodeStates[i], simplified.cfg.pk);
	        }
		}

    }

    static Connection getConnection(String nodeAddr) throws SQLException {
        String url = "jdbc:mysql://" + nodeAddr + "/" + simplified.cfg.dbName;
        System.out.println("Getting connection from " + url);
        Connection conn = DriverManager.getConnection(url, simplified.cfg.user, simplified.cfg.pass);
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        Statement stmt = conn.createStatement();
        stmt.execute("set session wsrep_sync_wait="+String.valueOf(cfg.wsrep_sync_wait));
//        stmt.execute("set session wsrep_causal_reads="+String.valueOf(cfg.wsrep_sync_wait));
        stmt.close();
        return conn;
    }

    static synchronized int getLatestValue() {
    	return simplified.latestVal;
    }

    static synchronized int readFromSource(NodeState nodeState) throws SQLException {
		if (nodeState.conn == null)
			nodeState.conn = getConnection(nodeState.addr);

		Statement stmt = nodeState.conn.createStatement();

		ResultSet rs = stmt.executeQuery("select val from snc where pk=1");
		rs.next();
		int newval = rs.getInt(1);
		rs.close();
		stmt.close();
		return newval;
    }

    static synchronized void setLatestValue(int newvalue) {
    	simplified.latestVal = newvalue;
    }

	static int increment(NodeState nodeState, String pk) throws SQLException {
		if (nodeState.conn == null)
			nodeState.conn = getConnection(nodeState.addr);
		Statement stmt = nodeState.conn.createStatement();
		int updatedval = getLatestValue() + 1;
		stmt.execute("update snc set val=" + updatedval
				+ ", ts=default where pk=" + pk);
		nodeState.conn.commit();
		ResultSet rs = stmt.executeQuery("select val from snc where pk=1;");
		rs.next();
		int newval = rs.getInt(1);
		setLatestValue(newval);
		rs.close();
		stmt.close();
		return newval;
	}

    static void queryAndReport(NodeState nodeState, String pk) throws SQLException {
        try {
            if( nodeState.conn == null )
                nodeState.conn = getConnection(nodeState.addr);

            Statement stmt = nodeState.conn.createStatement();

            // Checking the session variable wsrep_sync_wait before actually making a query.
            ResultSet rs1 = stmt.executeQuery("select @@session.wsrep_sync_wait;");
            String syncVariable = "";
            while(rs1.next()) {
            	syncVariable = rs1.getString(1);
            }

            if(syncVariable.trim().equals("0")) {
            	System.err.println("Session variable 'wsrep_sync_wait' is NOT set to 1");
            }
            rs1.close();


            rs1 = stmt.executeQuery("select @@session.wsrep_causal_reads;");
            while(rs1.next()) {
            	syncVariable = rs1.getString(1);
            }

            if(!syncVariable.trim().equals("0")) {
            	System.err.println("Session variable 'wsrep_causal_reads' is ON");
            }
            rs1.close();





            // Read from source first
            int valFromSource = readFromSource(sourceNode);
            ResultSet rs = stmt.executeQuery("select TIMESTAMPDIFF(SECOND, ts, NOW()), val from snc where pk=1");
            rs.next();
            int tsDiff  = rs.getInt(1);
            int currentVal = rs.getInt(2);
            int valDiff = valFromSource - currentVal;
            rs.close();

            rs = stmt.executeQuery(
                    "select lower(variable_name) as name, variable_value as val "+
                      "from information_schema.session_status "+
                     "where variable_name in('WSREP_LOCAL_RECV_QUEUE', 'WSREP_LOCAL_SEND_QUEUE', 'WSREP_LOCAL_REPLAYS', "+
                                "'WSREP_LOCAL_RECV_QUEUE_MAX', 'WSREP_LOCAL_SEND_QUEUE_MAX', 'WSREP_FLOW_CONTROL_PAUSED') "+
                      "order by variable_name");
            Map<String, Integer> sesStat = new HashMap<String, Integer>(10);
            while(rs.next()) {
                String name = rs.getString(1);
                int    val  = name.equals("wsrep_flow_control_paused") ? Math.round(rs.getFloat(2) * 1000000) : rs.getInt(2);
                sesStat.put(name, val);
            }
            rs.close();

            // Report
            nodeState.maxTsDiff = Math.max(nodeState.maxTsDiff,  tsDiff);
            if( valDiff > 0 ) {
                nodeState.maxValDiff = Math.max(nodeState.maxValDiff, valDiff);
                printf("%15s: val diff: %3d, src: %d, curr: %d, max ts diff %7.3f sec, recvQ max: %3d, sendQ max: %2d, pause %1.6f\n",
                        nodeState.addr,
                        valDiff,
                        valFromSource,
                        currentVal,
                        nodeState.maxTsDiff / 1000000f,
                        sesStat.get("wsrep_local_recv_queue_max"),
                        sesStat.get("wsrep_local_send_queue_max"),
                        sesStat.get("wsrep_flow_control_paused") / 1000000f
                );
                System.exit(1);
            }

        } catch(SQLException e) {
            printf("++ exception on %s: %s\n", nodeState.addr, e.getMessage());
            if( nodeState.conn != null ) {
                nodeState.conn.close();
                nodeState.conn = null;
            }
        }
    }

    static Cfg parseArgs(String[] args) {
        Cfg cfg = new Cfg();
        boolean help = false, err = false;

        for(int k=0; k < args.length; k++) {
            if( args[k].equals("-h") || args[k].equals("--help") ) { help = true; }
            else if( args[k].equals("-s") || args[k].equals("--sleep") ) { cfg.delay = Integer.parseInt(args[++k]); }
            else if( args[k].equals("-n") || args[k].equals("--nodes") ) { cfg.nodes = args[++k].split(","); }
            else if( args[k].equals("-b") || args[k].equals("--db") )   { cfg.dbName = args[++k]; }
            else if( args[k].equals("-u") || args[k].equals("--user") ) { cfg.user = args[++k]; }
            else if( args[k].equals("-p") || args[k].equals("--pass") ) { cfg.pass = args[++k]; }
            else if( args[k].equals("-w") || args[k].equals("--sw") )   { cfg.wsrep_sync_wait = args[++k]; }
            else if( args[k].equals("-k") || args[k].equals("--pk") )   { cfg.pk = args[++k]; }
            else {
                printfRaw("Unknown parameter %s\n", args[k]);
                err = true;
            }
        }

        if( help ) {
            printfRaw("Sync write tester (simplified)\n");
            printfRaw("Params:\n");
            printfRaw("  -h, --help:  this help\n");
            printfRaw("  -n, --nodes: comma-separated list of cluster node hostnames or ips, for ex.: node1,node2,node3\n");
            printfRaw("  -d, --db:    database name, mandatory\n");
            printfRaw("  -u, --user:  user name, default is %s\n", cfg.user);
            printfRaw("  -p, --pass:  passord, mandatory\n");
            printfRaw("  -s, --sleep: sleep delay between cycles in milliseconds, default is %d\n", cfg.delay);
            printfRaw("  -w, --sw:    wsrep_sync_wait parameter, 0 to 7, default is %s\n", cfg.wsrep_sync_wait);
            printfRaw("  -k, --pk:    PK of the record to update/query, default is %s\n", cfg.pk);
            return null;
        }

        if( !err ) {
            if( cfg.dbName == null || cfg.dbName.length() == 0 ) { printfRaw("Mandatory parameter --db is missing\n");  err = true; }
            if( cfg.pass   == null || cfg.pass.length() == 0 ) { printfRaw("Mandatory parameter --pass is missing\n");  err = true; }
            if( cfg.nodes  == null || cfg.nodes.length == 0 ) { printfRaw("Mandatory parameter --nodes is missing\n");  err = true; }
            if( cfg.wsrep_sync_wait.compareTo("0") < 0 || cfg.wsrep_sync_wait.compareTo("7") > 0 ) { printfRaw("Bad --sw value, expecting 0 to 7\n"); err = true; }
        }

        return err ? null : cfg;
    }
}
