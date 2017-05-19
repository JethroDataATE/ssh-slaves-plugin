package hudson.plugins.sshslaves;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Future;
import hudson.remoting.RequestAbortedException;
import hudson.slaves.Messages;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.trilead.ssh2.Connection;

import jenkins.model.Jenkins;


/**
 * @author Mosh
 */
@Extension
public class MachineMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(MachineMonitor.class.getName());

    private final Long recurrencePeriodMilliSec;
    private final Long pingTimeOutSecMilliSec;


	public MachineMonitor() {
        super("SSH alive slaves monitor");
        recurrencePeriodMilliSec = Long.getLong("jenkins.slaves.checkAlivePeriodMilliSec", 10000);
        pingTimeOutSecMilliSec = Long.getLong("jenkins.slaves.pingTimeOutMilliSec", 3000);
        LOGGER.log(Level.FINE, "check alive period is {0}ms", recurrencePeriodMilliSec);
    }

    @Override
    public long getRecurrencePeriod() {          
         return enabled ? (recurrencePeriodMilliSec > 10000 ? recurrencePeriodMilliSec : 20000) : TimeUnit2.DAYS.toMillis(30);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
    	if (!enabled)   return;
        for (Computer computer : Jenkins.getInstance().getComputers()) {        	
            if (computer instanceof SlaveComputer && !computer.isOffline()) {
                final SlaveComputer checkedcomputer = (SlaveComputer) computer;
                try {
                    if (!isAlive(checkedcomputer)) {
                        LOGGER.info("Slave is dead: " + checkedcomputer.getNode().getNodeName());
                        //checkedcomputer.terminate();
                        disconnectNode(checkedcomputer);
                    	if (checkedcomputer.getChannel() != null) {
                    		checkedcomputer.getChannel().terminate(new IOException());                    		
                    	}
                        LOGGER.info("Slave Disonnection is done: " + checkedcomputer.getNode().getNodeName());
                        PluginImpl.removeNodeToConnectionMap(checkedcomputer.getNode().getNodeName().trim());
                    }
                } catch (Exception e) {
                    LOGGER.info("Slave is dead and failed to terminate: " + checkedcomputer.getNode().getNodeName() + " message: " + e.getMessage());
                    
                }
            }
        }
    }

       
    private boolean isAlive(SlaveComputer checkedcomputer) {
    	
    	//LOGGER.info("Enter SSH slave monitor is isAlive: " + checkedcomputer.getNode().getNodeName());    		
  
    	if (checkedcomputer.getChannel() == null) {
    		//LOGGER.info(getTimestamp() +"Slave Channel is closed:  " + checkedcomputer.getNode().getNodeName());
    		return false;
    	}  
    	try {
    		Connection slaveConnection = PluginImpl.getNodeToConnectionMap().get(checkedcomputer.getNode().getNodeName().trim());
    		if (slaveConnection != null) {
        		//LOGGER.info(getTimestamp() +"starting ping:  " + checkedcomputer.getNode().getNodeName());
        		LOGGER.info(getTimestamp() +"ping with parameters:  " + checkedcomputer.getNode().getNodeName() + " connection : " + slaveConnection);
        		
        		ping(slaveConnection);	
    		} else {
    			throw new IOException("No Connection record was found for machine");
    		}
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} catch (InterruptedException e) {		
			e.printStackTrace();
			return false;
		}    	
    	LOGGER.info("Slave " + checkedcomputer.getNode().getNodeName() + " was last heard at " + checkedcomputer.getChannel().getLastHeard());
		return true;
    	
    }
    private void disconnectNode(SlaveComputer checkedSlave) {
        try {
        	checkedSlave.getChannel().close();        	
        	checkedSlave.disconnect(OfflineCause.create(Messages._ConnectionActivityMonitor_OfflineCause()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to disconnect Channel of Node: " + checkedSlave.getNode().getNodeName());
        }
    }


    // disabled by default 
    public boolean enabled = Boolean.getBoolean(MachineMonitor.class.getName()+".enabled");
    
    public int port = 22; 
    
    private void ping(Connection connection) throws IOException, InterruptedException {        
        long start = System.currentTimeMillis();
        long end = start + recurrencePeriodMilliSec - pingTimeOutSecMilliSec;

        
        String ipAddress = connection.getHostname();
        //LOGGER.info("got host name: " + ipAddress);
        long remaining;
        do {
            remaining = end-System.currentTimeMillis();
            try {                
                InetAddress inet = InetAddress.getByName(ipAddress);
                LOGGER.info("Sending Ping Request to " + ipAddress);
                LOGGER.info("respone to ping: " + inet.isReachable(pingTimeOutSecMilliSec.intValue()));
                if (inet.isReachable(pingTimeOutSecMilliSec.intValue())) {
                	return;	
                } else {
                	throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
                }
            } catch (IOException e) {
                throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
            }
        } while(remaining>0);
    }


    
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
