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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.swim.croupier.CroupierPort;
import se.kth.swim.croupier.msg.CroupierSample;
import se.kth.swim.msg.net.NetMsg;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Init;
import se.sics.kompics.Negative;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;
import se.sics.kompics.network.Header;
import se.sics.kompics.network.Network;
import se.sics.p2ptoolbox.util.network.NatedAddress;
import se.sics.p2ptoolbox.util.network.impl.RelayHeader;

/**
 *
 * @author Alex Ormenisan <aaor@sics.se>
 */
public class NatTraversalComp extends ComponentDefinition 
{

    private static final Logger log = LoggerFactory.getLogger(NatTraversalComp.class);
    private Negative<Network> local = provides(Network.class);
    private Positive<Network> network = requires(Network.class);
    private Positive<CroupierPort> croupier = requires(CroupierPort.class);

    
    
    private final NatedAddress selfAddress;
    private final long selfHash;
    
    private final Random rand;
    
    NatedAddress next,prev;
    
    HashSet<NatedAddress> partners=new HashSet<NatedAddress>();
    HashSet<NatedAddress> freshpartners=new HashSet<NatedAddress>();
    
    HashMap<NatedAddress,NatedAddress> cashe=new HashMap<NatedAddress ,NatedAddress>();
    ArrayList<NatedAddress> fingers;

	private Set<NatedAddress> opensample;

    public NatTraversalComp(NatTraversalInit init) 
    {
        this.selfAddress = init.selfAddress;
        this.selfHash=util.NATHash(selfAddress);
        log.info("{} {} initiating...", new Object[]{selfAddress.getId(), (selfAddress.isOpen() ? "OPEN" : "NATED")});

        this.rand = new Random(init.seed);
        subscribe(handleStart, control);
        subscribe(handleStop, control);
        subscribe(handleIncomingMsg, network);
        subscribe(handleOutgoingMsg, local);
        subscribe(handleCroupierSample, croupier);
        
        
    }

    private Handler<Start> handleStart = new Handler<Start>() {

        @Override
        public void handle(Start event) 
        {
            log.info("{} starting...", new Object[]{selfAddress.getId()});
            
        }

    };
    private Handler<Stop> handleStop = new Handler<Stop>() {

        @Override
        public void handle(Stop event) {
            log.info("{} stopping...", new Object[]{selfAddress.getId()});
        }

    };

    private Handler<NetMsg<Object>> handleIncomingMsg = new Handler<NetMsg<Object>>() {

        @Override
        public void handle(NetMsg<Object> msg) 
        {
            log.trace("{} received msg:{}", new Object[]{selfAddress.getId(), msg});
            spy(msg);
            if(msg.getHeader() instanceof Container)//from routing
            {
            	Container<NatedAddress> cont=(Container<NatedAddress>)msg.getHeader();
            	
            	if(partners.contains(cont.getActualDestination())||freshpartners.contains(cont.getActualDestination()))
            	{
            		//if is partner repack message
            		trigger(msg.copyMessage(new RelayHeader<NatedAddress>(cont.getActualHeader(),selfAddress)),network);//deliver to partners
            	}
            	else//do more routing
            	{
            		NatedAddress a=doRouting(cont.getActualDestination());
            		trigger(msg.copyMessage(new Container<NatedAddress>(cont.getActualHeader(),cont.getSource(),a)),network);
            	}
            }
            else//direct delivery
            {
            	if(selfAddress.isOpen())
            	{
            		trigger(msg,local);
            	}
            	else
            	{
            		//error

            	}
            }
        }

		private void spy(NetMsg<Object> msg) {
			// TODO Auto-generated method stub
			
		}

    };

   
    
    private Handler<NetMsg<Object>> handleOutgoingMsg = new Handler<NetMsg<Object>>() {

        @Override
        public void handle(NetMsg<Object> msg) 
        {
            log.trace("{} sending msg:{}", new Object[]{selfAddress.getId(), msg});
            Header<NatedAddress> header = msg.getHeader();
            if(header.getDestination().isOpen()) 
            {
                log.info("{} sending direct message:{} to:{}", new Object[]{selfAddress.getId(), msg, header.getDestination()});
                trigger(msg, network);
                return;
            } else 
            {
            	
            	//routing mode
            }
        }

	

    };

	private NatedAddress doRouting(NatedAddress dest)
	{
		NatedAddress ret=cashe.get(dest);
		if(ret!=null)
			return ret;
		else
		{
			ret=getPred(dest);
			if(ret!=null)
				return ret;
			else
				return randomNode(opensample);
		}
	}
    private Handler<CroupierSample<NatedAddress>> handleCroupierSample = new Handler<CroupierSample<NatedAddress>>() {
    	     	@Override
    	        public void handle(CroupierSample<NatedAddress> event)
    	     	{
    	            log.info("{} croupier public nodes:{}", selfAddress.getBaseAdr(), event.publicSample);
    	            //opensample=event.publicSample.;
    	            //TODO fix this so that randomNode gives a random public node
    	        }
    };
    	
    private NatedAddress randomNode(Set<NatedAddress> nodes) 
    {
        int index = rand.nextInt(nodes.size());
        Iterator<NatedAddress> it = nodes.iterator();
        while(index > 0)
        {
            it.next();
            index--;
        }
        
        return it.next();
    }

    public static class NatTraversalInit extends Init<NatTraversalComp>
    {

        public final NatedAddress selfAddress;
        public final long seed;

        public NatTraversalInit(NatedAddress selfAddress, long seed)
        {
            this.selfAddress = selfAddress;
            this.seed = seed;
        }
    }
    private long getDistTo(NatedAddress adr)
    {
    	long delta=util.NATHash(adr)-selfHash;
    	if(delta<0)
    		delta+=Long.MAX_VALUE;
    	return delta;
    }
    private NatedAddress getPred(NatedAddress adr)
    {
    	long delta=getDistTo(adr);
    	int best=0;
    	for(int i=0;i<fingers.size();i++)
    	{
    		if(fingers.get(i)==null)
    			continue;
    		long dist=getDistTo(fingers.get(i));
    		if(delta<=dist)
    			best++;
    		else
    			break;
    	}
    	return fingers.get(best);
    	
    }
}
