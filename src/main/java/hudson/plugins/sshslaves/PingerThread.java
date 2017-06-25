package hudson.plugins.sshslaves;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.trilead.ssh2.Connection;


public class PingerThread implements Runnable {
    
    /**
     * Time out in milliseconds.
     * If the response doesn't come back by then, the channel is considered dead.
     */    

    private Long recurrencePeriodMilliSec;
    private Long pingTimeOutSecMilliSec;
    private Connection connection = null;
    private Integer maxNumRetries;

    public PingerThread(Connection connection, long pingTimeOutSecMilliSec, long recurrencePeriodMilliSec, int maxNumRetries) {
        //super("Ping thread for channel "+channel);
        this.recurrencePeriodMilliSec = recurrencePeriodMilliSec;
        this.pingTimeOutSecMilliSec = pingTimeOutSecMilliSec;
        this.connection = connection;
        this.maxNumRetries = maxNumRetries;
    }

    public void run() {
        try {
            for (int i=0; i < maxNumRetries; i++) {
            	
                long nextCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recurrencePeriodMilliSec);

                if ((new Ping(recurrencePeriodMilliSec, pingTimeOutSecMilliSec, connection, maxNumRetries)).call()) {
                	return;
                }
                // wait until the next check
                long diff;
                while((diff = nextCheck - System.nanoTime()) > 0) {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(diff));
                }
            }
        } catch (IOException e) {
            LOGGER.info(getTimestamp() + connection.getHostname() +" is closed. Terminating");        
        } catch (InterruptedException e) {
            // use interruption as a way to terminate the ping thread.
            LOGGER.info(getTimestamp() + connection.getHostname()+" is interrupted. Terminating");
        }
    }
  
    public static final class Ping implements Callable<Boolean> {        
        private static Long recurrencePeriodMilliSec;
        private static Long pingTimeOutSecMilliSec;;
        private static Connection connection = null;
        private static Integer maxNumRetries;
        
        Ping (long recurrencePeriodMilliSec, long pingTimeOutSecMilliSec, Connection connection, int maxNumRetries) {
            Ping.recurrencePeriodMilliSec = recurrencePeriodMilliSec;
            Ping.pingTimeOutSecMilliSec = pingTimeOutSecMilliSec;
            Ping.connection = connection;
            Ping.maxNumRetries = maxNumRetries < 1 ? 1 : maxNumRetries;
        }
        public Boolean call() throws IOException {
            long start = System.currentTimeMillis();
            long end = start +  maxNumRetries * pingTimeOutSecMilliSec;

            
            String ipAddress = connection.getHostname();
            //LOGGER.info("got host name: " + ipAddress);
            long remaining;
            int trials = 1;
            do {
                remaining = end-System.currentTimeMillis();
                try {                
                    InetAddress inet = InetAddress.getByName(ipAddress);
                    LOGGER.info(getTimestamp() + "Sending Ping Request to " + ipAddress.trim());
                    if (inet.isReachable(pingTimeOutSecMilliSec.intValue())) {
                    	return true;	
                    } else {
                    	trials++;
                    	//throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
                    }
                } catch (IOException e) {
                    throw new IOException(getTimestamp() + "Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
                }
            } while(remaining>0 && trials <= maxNumRetries);
            return false;
        }

        public void checkRoles(RoleChecker checker) throws SecurityException {
            // this callable is literally no-op, can't get any safer than that
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MachineMonitor.class.getName());
    
    /**
     * Gets the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    @Restricted(NoExternalUse.class)
    public static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }
}
