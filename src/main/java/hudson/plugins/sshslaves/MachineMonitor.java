package hudson.plugins.sshslaves;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.PingerThread.Ping;
import hudson.slaves.Messages;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private final int maxNumRetries;
    private HashMap<String, java.util.concurrent.Future<?>> pingProccesedList = new LinkedHashMap<String, java.util.concurrent.Future<?>>();
    ExecutorService executor = Executors.newCachedThreadPool(); 


	public MachineMonitor() {
        super("SSH alive slaves monitor");
        recurrencePeriodMilliSec = Long.getLong("jenkins.slaves.checkAlivePeriodMilliSec", 20000);
        pingTimeOutSecMilliSec = Long.getLong("jenkins.slaves.pingTimeOutMilliSec", 10000);
        maxNumRetries = Integer.getInteger("jenkins.slaves.maxNumRetries", 2);
        LOGGER.log(Level.FINE, getTimestamp() + " check alive period is {0}ms", recurrencePeriodMilliSec);
        
    }

    // disabled by default 
    public boolean enabled = Boolean.getBoolean(MachineMonitor.class.getName()+".enabled");
    
    @Override
    public long getRecurrencePeriod() {          
         return enabled ? (recurrencePeriodMilliSec > 10000 ? recurrencePeriodMilliSec : 20000) : TimeUnit2.DAYS.toMillis(30);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
    	if (!enabled)   return;
    	// trigger ping for all available computers
    	triggerPing (Jenkins.getInstance().getComputers());
    	
    	long start = System.currentTimeMillis();
    	long remaining = recurrencePeriodMilliSec;
    	
		do {
			remaining = remaining - (System.currentTimeMillis() - start);
			for (Computer currentComputer : Jenkins.getInstance().getComputers()) {
				if (currentComputer instanceof SlaveComputer && !currentComputer.isOffline()) {
					Boolean isSlaveAlive = null;
					final SlaveComputer checkedcomputer = (SlaveComputer) currentComputer;	
					//LOGGER.info("handling Slave: " + checkedcomputer.getNode().getNodeName());
					java.util.concurrent.Future<?> pingTask = pingProccesedList.get(checkedcomputer.getNode().getNodeName().trim());
					if (pingTask == null) continue;
					if (!pingTask.isDone()) {
						//LOGGER.info(getTimestamp() +"Wait for ping task for :  " + checkedcomputer.getNode().getNodeName().trim() + " will finish within : " + );
						isSlaveAlive = waitToGetResult(pingTask, remaining/pingProccesedList.size());
						LOGGER.info(getTimestamp() +" result for ping to "  + checkedcomputer.getNode().getNodeName().trim() + " is: "+ isSlaveAlive + "  waited for: " + remaining/pingProccesedList.size() + "milli");
						if (isSlaveAlive == null) continue;
					} else {
						try {
							try {
								isSlaveAlive = (Boolean) pingTask.get(100, TimeUnit.MILLISECONDS);
								LOGGER.info(getTimestamp() +" result for Completed ping task to "  + checkedcomputer.getNode().getNodeName().trim() + " is: "+ isSlaveAlive + "  waited for: " + remaining/pingProccesedList.size() + "milli");
							} catch (TimeoutException e) {
								LOGGER.info(getTimestamp() + " could not get ping result timeout though task completed " + checkedcomputer.getNode().getNodeName());
							}
						} catch (ExecutionException e) {						
							isSlaveAlive = false;
						}
					}
					// if ping task is done and there is a result of true or false we can determine whether terminating this slave
                    if (!isSlaveAlive/* && !remoteIPfileExist(checkedcomputer)*/) {
                       // LOGGER.info(getTimestamp() + "Slave is dead: " + checkedcomputer.getNode().getNodeName());
                        //checkedcomputer.terminate();
                        disconnectNode(checkedcomputer);
                    	if (checkedcomputer.getChannel() != null) {
                    		checkedcomputer.getChannel().terminate(new IOException());                    		
                    	}
                        LOGGER.info(getTimestamp() + " Slave Disonnection is done: " + checkedcomputer.getNode().getNodeName());
                        PluginImpl.removeNodeToConnectionMap(checkedcomputer.getNode().getNodeName().trim());
                        pingProccesedList.remove(checkedcomputer.getNode().getNodeName().trim());
                    } else {
                    	LOGGER.info(getTimestamp() + " Slave " + checkedcomputer.getNode().getNodeName() + "is Alive : " + isSlaveAlive + " or remote workspace exist : " + remoteIPfileExist(checkedcomputer));
                    	// remove ping request task if successful
                    	pingProccesedList.remove(checkedcomputer.getNode().getNodeName().trim());
                    }
				}
			}
    	} while (remaining > 0);
		
		// clean previous node to ping tasks hash map
		//pingProccesedList.clear();
    }

      
    private Boolean waitToGetResult (java.util.concurrent.Future<?> pingTask, long timeout) {    	
		try {			
			return (Boolean) pingTask.get(timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			return null;		
		} catch (ExecutionException e) {
			return null;
		} catch (TimeoutException e) {			
			return false;
		}		
	}
    	
    private void triggerPing (Computer[] computers) {
		
    	for (Computer computer : computers) {
    		if (computer instanceof SlaveComputer && !computer.isOffline()) {
	    		Connection slaveConnection = PluginImpl.getNodeToConnectionMap().get(computer.getNode().getNodeName().trim());
	    		if (slaveConnection != null) {
	        		//LOGGER.info(getTimestamp() +"starting ping:  " + checkedcomputer.getNode().getNodeName());
	        		LOGGER.info(getTimestamp() +" submit task of ping with parameters:  " + computer.getNode().getNodeName() + " connection : " + slaveConnection.getHostname());  
					//java.util.concurrent.Future<?> f = executor.submit(new PingerThread(slaveConnection, pingTimeOutSecMilliSec, recurrencePeriodMilliSec, maxNumRetries));
					java.util.concurrent.Future<?> f = executor.submit(new Ping(recurrencePeriodMilliSec, pingTimeOutSecMilliSec, slaveConnection, maxNumRetries));
					pingProccesedList.put(computer.getNode().getNodeName().trim(), f);
					//LOGGER.info(getTimestamp() +"ping task submitted and added to list ");
	    		}
    		}
    	}
     	
    }
    
    
    private void disconnectNode(SlaveComputer checkedSlave) {
        try {
        	checkedSlave.getChannel().close();        	
        	checkedSlave.disconnect(OfflineCause.create(Messages._ConnectionActivityMonitor_OfflineCause()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, getTimestamp() + "Failed to disconnect Channel of Node: " + checkedSlave.getNode().getNodeName());
        }
    }

  
    public int port = 22; 
    
	private boolean remoteIPfileExist(final SlaveComputer computer) throws IOException, InterruptedException {	
		if (computer.getChannel().isInClosed() || computer.getChannel().isOutClosed()) return false;
		FilePath root = new FilePath(computer.getChannel(),computer.getNode().getRemoteFS());
		try {
			return new FilePath(root, "IP").exists();	
		} catch(Exception e) {
			return false;
		}		       
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
