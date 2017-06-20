package hudson.plugins.sshslaves;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.PingerThread.Ping;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final int maxNumRetries;
    private HashMap<String, java.util.concurrent.Future<?>> pingProccesedList = new LinkedHashMap<String, java.util.concurrent.Future<?>>();
    ExecutorService executor = Executors.newCachedThreadPool(); 


	public MachineMonitor() {
        super("SSH alive slaves monitor");
        recurrencePeriodMilliSec = Long.getLong("jenkins.slaves.checkAlivePeriodMilliSec", 10000);
        pingTimeOutSecMilliSec = Long.getLong("jenkins.slaves.pingTimeOutMilliSec", 3000);
        maxNumRetries = Integer.getInteger("jenkins.slaves.maxNumRetries", 2);
        LOGGER.log(Level.FINE, "check alive period is {0}ms", recurrencePeriodMilliSec);
    }

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
/*        for (Computer computer : Jenkins.getInstance().getComputers()) {        	
            if (computer instanceof SlaveComputer && !computer.isOffline()) {
                final SlaveComputer checkedcomputer = (SlaveComputer) computer;
                try {
                    if (!isAlive(checkedcomputer) && !remoteIPfileExist(checkedcomputer)) {
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
        }*/
    	
		do {
			remaining = remaining - (System.currentTimeMillis() - start);
			for (Computer currentComputer : Jenkins.getInstance().getComputers()) {
				if (currentComputer instanceof SlaveComputer && !currentComputer.isOffline()) {
					Boolean isSlaveAlive = null;
					final SlaveComputer checkedcomputer = (SlaveComputer) currentComputer;	
					LOGGER.info("handling Slave: " + checkedcomputer.getNode().getNodeName());
					java.util.concurrent.Future<?> pingTask = pingProccesedList.get(checkedcomputer.getNode().getNodeName().trim());
					if (!pingTask.isDone()) {
						LOGGER.info(getTimestamp() +"Wait for ping task for :  " + checkedcomputer.getNode().getNodeName().trim() + " will finish within : " + remaining/pingProccesedList.size() + "milli");
						isSlaveAlive = waitToGetResult(pingTask, remaining/pingProccesedList.size());
						LOGGER.info(getTimestamp() +"result for ping to "  + checkedcomputer.getNode().getNodeName().trim() + " is: "+ isSlaveAlive);
						if (isSlaveAlive == null) continue;
					}
					// if ping task is done and there is a result of true or false we can determine whether terminating this slave
                    if (!isSlaveAlive/* && !remoteIPfileExist(checkedcomputer)*/) {
                        LOGGER.info("Slave is dead: " + checkedcomputer.getNode().getNodeName());
                        //checkedcomputer.terminate();
                        disconnectNode(checkedcomputer);
                    	if (checkedcomputer.getChannel() != null) {
                    		checkedcomputer.getChannel().terminate(new IOException());                    		
                    	}
                        LOGGER.info("Slave Disonnection is done: " + checkedcomputer.getNode().getNodeName());
                        PluginImpl.removeNodeToConnectionMap(checkedcomputer.getNode().getNodeName().trim());
                    } else {
                    	LOGGER.info("Slave " + checkedcomputer.getNode().getNodeName() + "is Alive : " + isSlaveAlive + " or remote workspace exist : " + remoteIPfileExist(checkedcomputer));
                    }
				}
			}
    	} while (remaining > 0);
		
		// clean previous node to ping tasks hash map
		pingProccesedList.clear();
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
	        		LOGGER.info(getTimestamp() +"submit task of ping with parameters:  " + computer.getNode().getNodeName() + " connection : " + slaveConnection);  
					//java.util.concurrent.Future<?> f = executor.submit(new PingerThread(slaveConnection, pingTimeOutSecMilliSec, recurrencePeriodMilliSec, maxNumRetries));
					java.util.concurrent.Future<?> f = executor.submit(new Ping(recurrencePeriodMilliSec, pingTimeOutSecMilliSec, slaveConnection, maxNumRetries));
					pingProccesedList.put(computer.getNode().getNodeName().trim(), f);
					LOGGER.info(getTimestamp() +"ping task submitted and added to list ");
	    		}
    		}
    	}
     	
    }
    
    /*	try {
		result = (Boolean) f.get(Math.max(1,pingTimeOutSecMilliSec * maxNumRetries),TimeUnit.MILLISECONDS);
	} catch (InterruptedException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	} catch (ExecutionException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	} catch (TimeoutException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}*/
    
/*    private boolean isAlive(SlaveComputer checkedcomputer) {
    	
    	//LOGGER.info("Enter SSH slave monitor is isAlive: " + checkedcomputer.getNode().getNodeName());    		
  
    	// if we got here the slave is online so mostly will have a channel, more over ping test is stronger
    	if (checkedcomputer.getChannel() == null) {
    		LOGGER.info(getTimestamp() +"Slave Channel is closed:  " + checkedcomputer.getNode().getNodeName());
    		return false;
    	}  	
    	
    	try {
    		Connection slaveConnection = PluginImpl.getNodeToConnectionMap().get(checkedcomputer.getNode().getNodeName().trim());
    		if (slaveConnection != null) {
        		//LOGGER.info(getTimestamp() +"starting ping:  " + checkedcomputer.getNode().getNodeName());
        		LOGGER.info(getTimestamp() +"ping with parameters:  " + checkedcomputer.getNode().getNodeName() + " connection : " + slaveConnection);
        		
        		//ping(slaveConnection);
        		Boolean result = null;
				java.util.concurrent.Future<Boolean> f = executor.submit(new PingerThread(slaveConnection, pingTimeOutSecMilliSec, recurrencePeriodMilliSec, maxNumRetries), result);
	            try {	                
	            	result = f.get(Math.max(1,pingTimeOutSecMilliSec * maxNumRetries),TimeUnit.MILLISECONDS);
	            } catch (ExecutionException e) {
	            	LOGGER.info(getTimestamp() +"ping execution error to:" + checkedcomputer.getNode().getNodeName());
	               return false;
	            } catch (InterruptedException e) {
	            	return false;					
				} catch (TimeoutException e) {
					LOGGER.info(getTimestamp() +"ping timed Out to:" + checkedcomputer.getNode().getNodeName());
					return false;
				}
	            LOGGER.info("Slave " + checkedcomputer.getNode().getNodeName() + " was last heard at " + checkedcomputer.getChannel().getLastHeard());
	            return result;
    		} else {
    			throw new IOException("No Connection record was found for machine");
    		}
		} catch (IOException e) {			
			return false;
		}
    }*/
    
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
    
/*    private void ping(Connection connection) throws IOException, InterruptedException {        
        long start = System.currentTimeMillis();
        long end = start + recurrencePeriodMilliSec - pingTimeOutSecMilliSec;

        
        String ipAddress = connection.getHostname();
        //LOGGER.info("got host name: " + ipAddress);
        long remaining;
        do {
            remaining = end-System.currentTimeMillis();
            try {                
                InetAddress inet = InetAddress.getByName(ipAddress);
                LOGGER.info("Sending Ping Request to " + ipAddress.trim());
                //LOGGER.info("respone to ping: " + inet.isReachable(pingTimeOutSecMilliSec.intValue()));
                if (inet.isReachable(pingTimeOutSecMilliSec.intValue())) {
                	return;	
                } else {
                	throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
                }
            } catch (IOException e) {
                throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
            }
        } while(remaining>0);
    }*/


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
