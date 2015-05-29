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

import se.kth.swim.msg.Ping;
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
	private static final long  maxRTTdir=400; //i think it is ms
	
    private Positive<Network> network = requires(Network.class);
    private Positive<Timer> timer = requires(Timer.class);

    private final NatedAddress selfAddress;
    ArrayList<NatedAddress> Nodes=new ArrayList<NatedAddress>();	
    private final NatedAddress aggregatorAddress;

    private UUID RTTID;
    private UUID pingTimeoutId;
    private UUID statusTimeoutId;

    private int receivedPings = 0;
    
    public  HashMap<NatedAddress,UUID> susepects =new HashMap<NatedAddress,UUID>();


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
        subscribe(susptimeout,timer);
        
        for(NatedAddress a:init.bootstrapNodes)
        {
        	nodeStatus.put(a,new Status(Status.UNKNOWN,ts));
        }
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
            log.debug("{} received ping from:{}, ping={}", new Object[]{selfAddress.getId(), event.getHeader().getSource().getId(),event.getContent().toString()});
            receivedPings++;
            
            NodeAlive(event.getSource());
            GetPiggyStatus(event.getContent().data);

            if(event.getContent().dest==selfAddress)
            {
	            log.debug("{} sending pong to:{}", new Object[]{selfAddress.getId(), event.getHeader().getSource()});
	            if(event.getContent().Proxy!=null)
	            	trigger(new NetPong(selfAddress, event.getContent().Proxy,new Pong(event.getContent(), getGossip())), network);
	            else
		            trigger(new NetPong(selfAddress, event.getContent().sender,new Pong(event.getContent(), getGossip())), network);

            }
            
            else
                if(event.getContent().Proxy==selfAddress)
                {
    	            trigger(new NetPing(selfAddress, event.getContent().dest,new Ping(event.getContent().sender,event.getContent().dest,selfAddress,getGossip())), network);
                }
                else
                    log.debug("{} received ping from:{}, ping was missdeliverd", new Object[]{selfAddress.getId(), event.getHeader().getSource()});

        }

    };
    private Handler<NetPong> handlePong = new Handler<NetPong>() {

        @Override
        public void handle(NetPong event) 
        {
            log.debug("{} received pong from:{} ,dest={}", new Object[]{selfAddress.getId(), event.getHeader().getSource() ,event.getContent()});
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
            ts++;
            log.debug("{} sending ping to partner:{}", new Object[]{selfAddress.getId(), partnerAdr});
            HashMap<NatedAddress,Status> gossip=getGossip();
            Ping p=new Ping(selfAddress,partnerAdr,null,gossip);
            trigger(new NetPing(selfAddress, partnerAdr,p), network);
            scheduleRTTtimeout(p,false);

        }

    };
    private Handler<Suspect_timeout> susptimeout=new Handler <Suspect_timeout>()
	{
    	@Override
    	public void handle(Suspect_timeout event)
    	{
    		if(nodeStatus.get(event.TargetAdr).isAlive())
    			return;
    		else
    		{
	    		NodeDead(event.TargetAdr);
	            log.info("{} is found dead by {} at time {}", new Object[]{event.TargetAdr,selfAddress.getId(), ts});
    		}
    	}
	};
    private Handler<RTT_Timeout> handleRTTTimeout = new Handler<RTT_Timeout>() 
	{

        @Override
        public void handle(RTT_Timeout event)
        {
            log.debug("{} did not revive pong from {}", new Object[]{selfAddress.getId(), event.ping.dest});
            //NodeSusp(event.TargetAdr);
            if(!event.Proxy)
            {
            	log.debug(" indirect...", new Object[]{selfAddress.getId()});
            	k_inderectPings(event.ping,10);
            }
            else
            	NodeSusp(event.ping.dest);
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
    private void scheduleRTTtimeout(Ping p, boolean b)
    {
    	ScheduleTimeout ST;
    	if(b)
    		ST=new ScheduleTimeout(maxRTTdir);
    	else
    		ST=new ScheduleTimeout(2*maxRTTdir);

    	RTT_Timeout t=new RTT_Timeout(ST,p,b);
    	ST.setTimeoutEvent(t);
    	RTTID=t.getTimeoutId();
    	trigger(ST,timer);
    }
    protected void k_inderectPings(Ping p, int k)
    {

    	log.debug(" indirect...", new Object[]{selfAddress.getId()});
    	for(int i=0;i<k;i++)
    	{
        	NatedAddress inderect_adr=getRandomNode();
    		trigger(new NetPing(selfAddress,inderect_adr,p.useProxy(inderect_adr)), network);
    	}
    	scheduleRTTtimeout(p, true);
    	
	}
	private void cancelRTTtimeout()
	{
		if(pingTimeoutId!=null)
		{
	        CancelTimeout ct = new CancelTimeout(RTTID);
	        trigger(ct, timer);
	        pingTimeoutId = null;
		}
    }

    private void schedulePeriodicPing()
    {
        SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(1000, 10*maxRTTdir);
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
    		if(adr!=selfAddress)
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
        log.debug("{} unpack gossip:{}", new Object[]{selfAddress.getId(), piggydata.size()});
        int newgossip=0;
    	for(NatedAddress adr:piggydata.keySet())
    	{
    		Status old_s=nodeStatus.get(adr);
    		Status new_s=piggydata.get(adr);
    		if(old_s==null)
    		{
    			nodeStatus.put(adr, new_s);
    			Nodes.add(adr);
    			newgossip++;
    			continue;
    		}
    		if (old_s.time<new_s.time)
    		{
    			nodeStatus.put(adr, new_s);
    			if(new_s.time>ts)
    			{
    				ts=new_s.time;
    				newgossip++;
    			}
    		}
    		
    		
    	} 
    	log.debug("{} items of new gossip", new Object[]{newgossip});

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
    	Status s=nodeStatus.get(source);
    	
    	//if(s!=null&&!s.isSusp()&&!s.isDead())	//if change
    	if(s!=null&&s.isAlive())
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.SUSP,ts)));
    	if(s==null)
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.SUSP,ts)));
    	if(s.isSusp()||s.isDead())
    		return;
    	else
    	{
    	nodeStatus.put(source, new Status(Status.SUSP,ts));
    	this.printStatus();
    	scheduleSuspTimeout(source, 5*maxRTTdir);
    	}
    	
	}
    protected void NodeDead(NatedAddress source) 
    {
    	Status s=nodeStatus.get(source);
    	if(s!=null&&!s.isDead())	//if change
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.DEAD,ts)));
    	if(s==null)
    		Delta.add(new Pair<NatedAddress,Status>(source, new Status(Status.DEAD,ts)));

    	nodeStatus.put(source, new Status(Status.DEAD,ts));
    	this.printStatus();
	}
    /**
     * use for get some recent data for gossiping 
     * 
     * */
    private HashMap<NatedAddress,Status> getGossip()
    {
        log.debug("{} gets items of gossip:{}", new Object[]{selfAddress.getId(), Delta.size()});

    	HashMap<NatedAddress,Status> ret=new HashMap<NatedAddress,Status>();
    	for(Pair<NatedAddress,Status> p:Delta)
    	{
    		ret.put(p.first,p.second);
    	}
    	if(Delta.size()>lambda*util.binlog(nodeStatus.size()))
    		Delta.poll();
    	
    	//return ret;
    	return nodeStatus;
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
            log.info("periodic ping set ");
        }
        
    }
    private static class RTT_Timeout extends Timeout
    {
    	public final boolean Proxy;
    	public final Ping ping;
    	
		public RTT_Timeout(ScheduleTimeout sT,Ping p, boolean b)
        {
            super(sT);
            ping=p;
            Proxy=b;
        }
    }
    private static class Suspect_timeout extends Timeout
    {
    	public final NatedAddress TargetAdr;
		public Suspect_timeout(ScheduleTimeout sT,NatedAddress target)
        {
            super(sT);
            TargetAdr=target;
        }

    }
	public void scheduleSuspTimeout(NatedAddress target, long time)
	{
    	ScheduleTimeout ST=new ScheduleTimeout(time);
    	Suspect_timeout t=new Suspect_timeout(ST,target);
    	ST.setTimeoutEvent(t);
    	susepects.put(target,t.getTimeoutId());
    	trigger(ST,timer);
	}
	private void cancelSuspTimeout(NatedAddress target)
	{
		UUID timeoutid=susepects.get(target);
		if(timeoutid==null)
			return;
		CancelTimeout cpt = new CancelTimeout(timeoutid);
		susepects.remove(target);
        trigger(cpt, timer);
	}
    public void printStatus()
    {
    	int A=0,S=0,D=0;
    	for(NatedAddress adr:nodeStatus.keySet())
    	{
    		int status=nodeStatus.get(adr).Status;
    		if(status==Status.ALIVE)
    			A++;
    		if(status==Status.SUSP)
    			S++;
    		if(status==Status.DEAD)
    			D++;
    			
    	}
        log.info("{} has {} Alive, {} Suspected, {} Dead at time {}", new Object[]{selfAddress,A,S,D,ts });
        log.debug("nodes are :"+ Arrays.toString(nodeStatus.keySet().toArray()), new Object[]{ });

    }
}
