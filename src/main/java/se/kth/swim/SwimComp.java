/*
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 *
 * GVoD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.kth.swim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.swim.msg.Pong;
import se.kth.swim.msg.Status;
import se.kth.swim.msg.net.NetPing;
import se.kth.swim.msg.net.NetPong;
import se.kth.swim.msg.net.NetStatus;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Init;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;
import se.sics.kompics.network.Network;
import se.sics.kompics.timer.CancelTimeout;
import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.ScheduleTimeout;
import se.sics.kompics.timer.Timeout;
import se.sics.kompics.timer.Timer;
import se.sics.p2ptoolbox.util.network.NatedAddress;

/**
 * @author Alex Ormenisan <aaor@sics.se>
 */
public class SwimComp extends ComponentDefinition {

    private static final Logger log = LoggerFactory.getLogger(SwimComp.class);
	private static final double lambda =2.0;
	private static final long  maxRTTdir=1000; //i think it is ms
	
    private Positive<Network> network = requires(Network.class);
    private Positive<Timer> timer = requires(Timer.class);

    private final NatedAddress selfAddress;
    ArrayList<NatedAddress> Nodes=new ArrayList<NatedAddress>();	
    private final NatedAddress aggregatorAddress;

    private UUID RTTID;
    private UUID pingTimeoutId;
    private UUID statusTimeoutId;

    private int receivedPings = 0;

    private HashMap<NatedAddress,Status> nodeStatus;
    private Queue<Pair<NatedAddress,Status> > Delta;
    
    private Set<NatedAddress> openAddresses;
    private Set<NatedAddress> RestrictedAddresses;
    
    private long ts;//time stamp
    Random rand=new Random();
    
    public SwimComp(SwimInit init) 
    {
        this.selfAddress = init.selfAddress;
        log.info("{} initiating...", selfAddress);
        this.Nodes.addAll( init.bootstrapNodes);
        this.aggregatorAddress = init.aggregatorAddress;
        
        
        ArrayList<NatedAddress> Nodes;						//all know nodes
        nodeStatus=new HashMap<NatedAddress,Status>();		//nodes and status
        Delta =new LinkedList<Pair<NatedAddress,Status> >();//recent changes

        
        openAddresses =new HashSet<NatedAddress>();
        RestrictedAddresses =new HashSet<NatedAddress>();

        subscribe(handleStart, control);
        subscribe(handleStop, control);
        subscribe(handlePing, network);
        subscribe(handlePong, network);
        subscribe(handlePeriodicPingTimeout, timer);
        subscribe(handleStatusTimeout, timer);
        subscribe(handleRTTTimeout,timer);
    }

    private Handler<Start> handleStart = new Handler<Start>() {

        @Override
        public void handle(Start event) 
        {
            log.info("{} starting... {}", new Object[]{selfAddress.getId()},"\t "+Nodes.size()+" startNodes");

            if (!Nodes.isEmpty())
            {
                schedulePeriodicPing();
            }
            schedulePeriodicStatus();
        }

    };
    private Handler<Stop> handleStop = new Handler<Stop>() {

        @Override
        public void handle(Stop event) {
            log.info("{} stopping...", new Object[]{selfAddress.getId()});
            if (pingTimeoutId != null) {
                cancelPeriodicPing();
            }
            if (statusTimeoutId != null) {
                cancelPeriodicStatus();
            }
        }

    };
    /**
     * Handles incoming ping
     * */
    private Handler<NetPing> handlePing = new Handler<NetPing>() {

        @Override
        public void handle(NetPing event) 
        {
            log.info("{} received ping from:{}", new Object[]{selfAddress.getId(), event.getHeader().getSource()});
            receivedPings++;
            
            NodeAlive(event.getSource());
            GetPiggyStatus(event.getContent().data);
            
            log.info("{} sending pong to:{}", new Object[]{selfAddress.getId(), event.getHeader().getSource()});
            trigger(new NetPong(selfAddress, event.getSource(),new Pong(selfAddress,getGossip())), network);
        }

    };
    private Handler<NetPong> handlePong = new Handler<NetPong>() {

        @Override
        public void handle(NetPong event) 
        {
            log.info("{} received pong from:{}", new Object[]{selfAddress.getId(), event.getHeader().getSource()});
            NodeAlive(event.getSource());
            GetPiggyStatus(event.getContent().data);
            cancelRTTtimeout();
        }

    };
    /**
     * the periodic ping trigger
     * */
    private Handler<PeriodicPingTimer> handlePeriodicPingTimeout = new Handler<PeriodicPingTimer>() {

        @Override
        public void handle(PeriodicPingTimer event)
        {
        	NatedAddress partnerAdr=getRandomNode();
            
            log.info("{} sending ping to partner:{}", new Object[]{selfAddress.getId(), partnerAdr});
            
            trigger(new NetPing(selfAddress, partnerAdr), network);
            scheduleRTTtimeout(partnerAdr);

        }

    };
    private Handler<RTT_Timeout> handleRTTTimeout = new Handler<RTT_Timeout>() 
	{

        @Override
        public void handle(RTT_Timeout event)
        {
            log.info("{} did not revive pong from {}", new Object[]{selfAddress.getId(), event.TargetAdr});

        }
        
    };

    
    private Handler<StatusTimeout> handleStatusTimeout = new Handler<StatusTimeout>() {

        @Override
        public void handle(StatusTimeout event)
        {
            log.info("{} sending status to aggregator:{}", new Object[]{selfAddress.getId(), aggregatorAddress});
            trigger(new NetStatus(selfAddress, aggregatorAddress, new Status(receivedPings)), network);
        }

    };
    private void scheduleRTTtimeout(NatedAddress x)
    {
    	ScheduleTimeout ST=new ScheduleTimeout(maxRTTdir);
    	RTT_Timeout t=new RTT_Timeout(ST,x);
    	ST.setTimeoutEvent(t);
    	RTTID=t.getTimeoutId();
    	trigger(ST,timer);
    }
    private void cancelRTTtimeout()
	{
        CancelTimeout ct = new CancelTimeout(RTTID);
        trigger(ct, timer);
        pingTimeoutId = null;
    }

    private void schedulePeriodicPing()
    {
        SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(1000, 1000);
        PeriodicPingTimer sc = new PeriodicPingTimer(spt);
        spt.setTimeoutEvent(sc);
        pingTimeoutId = sc.getTimeoutId();
        trigger(spt, timer);
        log.info("{} scheduling...", new Object[]{selfAddress.getId()});

    }
    protected NatedAddress getRandomNode() 
    {
    	NatedAddress adr=selfAddress;
    	while(true)
    	{
    		adr=Nodes.get(rand.nextInt(Nodes.size()));
    		if(nodeStatus.get(adr)==null||nodeStatus.get(adr).Status!=Status.DEAD)
    			break;
    	}
    	

 		
		return adr;
	}
	private boolean tryInsert(NatedAddress adr,Status s)
    {
    	Status stored=nodeStatus.get(adr);
    	if (stored!=null&&stored.Status==s.Status)
    		if(stored.time<s.time)
    		{
    			nodeStatus.put(adr, s);
    			return true;
    		}
    			
		return false;
    }

    protected void GetPiggyStatus(HashMap<NatedAddress,Status> piggydata)
    {
        log.info("{} unpack gossip:{}", new Object[]{selfAddress.getId(), piggydata.size()});

    	for(NatedAddress adr:piggydata.keySet())
    	{
    		Status old_s=nodeStatus.get(adr);
    		Status new_s=piggydata.get(adr);
    		if(old_s==null)
    		{
    			nodeStatus.put(adr, new_s);
    			continue;
    		}
    		if (old_s.time<new_s.time)
    		{
    			nodeStatus.put(adr, new_s);
    		}
    		
    	} 
    }
    /**
     * use for when receiving a message from someone
     * */
    protected void NodeAlive(NatedAddress source) 
    {
    	Status s=nodeStatus.get(source);
    	if(s!=null&&!s.isAlive())	//if change
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.ALIVE,ts)));
    	if(s==null)
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.ALIVE,ts)));

    	nodeStatus.put(source, new Status(Status.ALIVE,ts));
    	this.printStatus();
	}
    protected void NodeSusp(NatedAddress source) 
    {
		
	}
    protected void NodeDead(NatedAddress source) 
    {
    	
	}
    /**
     * use for get some recent data for gossiping 
     * 
     * */
    private HashMap<NatedAddress,Status> getGossip()
    {
        log.info("{} gets items of gossip:{}", new Object[]{selfAddress.getId(), Delta.size()});

    	HashMap<NatedAddress,Status> ret=new HashMap<NatedAddress,Status>();
    	for(Pair<NatedAddress,Status> p:Delta)
    	{
    		ret.put(p.first,p.second);
    	}
    	if(Delta.size()>lambda*util.binlog(nodeStatus.size()))
    		Delta.poll();
    	
    	return ret;
    }

	private void cancelPeriodicPing()
	{
        CancelTimeout cpt = new CancelTimeout(pingTimeoutId);
        trigger(cpt, timer);
        pingTimeoutId = null;
    }

    private void schedulePeriodicStatus()
    {
        SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(10000, 10000);
        StatusTimeout sc = new StatusTimeout(spt);
        spt.setTimeoutEvent(sc);
        statusTimeoutId = sc.getTimeoutId();
        trigger(spt, timer);
    }

    private void cancelPeriodicStatus() 
    {
        CancelTimeout cpt = new CancelTimeout(statusTimeoutId);
        trigger(cpt, timer);
        statusTimeoutId = null;
    }

    public static class SwimInit extends Init<SwimComp>
    {

        public final NatedAddress selfAddress;
        public final Set<NatedAddress> bootstrapNodes;
        public final NatedAddress aggregatorAddress;

        public SwimInit(NatedAddress selfAddress, Set<NatedAddress> bootstrapNodes, NatedAddress aggregatorAddress)
        {
            this.selfAddress = selfAddress;
            this.bootstrapNodes = bootstrapNodes;
            this.aggregatorAddress = aggregatorAddress;
        }
    }

    private static class StatusTimeout extends Timeout
    {

        public StatusTimeout(SchedulePeriodicTimeout request)
        {
            super(request);
        }
    }   
    private static class PeriodicPingTimer extends Timeout
    {
        public PeriodicPingTimer(SchedulePeriodicTimeout request)
        {
            super(request);
            log.info("peridic ping set ");
        }
        
    }
    private static class RTT_Timeout extends Timeout
    {
    	public final NatedAddress TargetAdr;
    	public final NatedAddress Proxy;
		public RTT_Timeout(ScheduleTimeout sT,NatedAddress target)
        {
            super(sT);
            TargetAdr=target;
            Proxy=null;
        }
    }
    public void printStatus()
    {
    	int A=0,S=0,D=0;
    	for(NatedAddress adr:nodeStatus.keySet())
    	{
    		int status=nodeStatus.get(adr).Status;
    		if(status==Status.ALIVE)
    			A++;
    			
    	}
        log.info("{} has {} Alive, {} Suspected, {} Dead", new Object[]{selfAddress,A,S,D });
        log.info("nodes are :"+ Arrays.toString(nodeStatus.keySet().toArray()), new Object[]{ });

    }
}
