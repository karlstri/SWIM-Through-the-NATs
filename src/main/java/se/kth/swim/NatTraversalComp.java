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
import se.kth.swim.croupier.internal.CroupierShuffle;
import se.kth.swim.croupier.internal.CroupierShuffle.Response;
import se.kth.swim.croupier.internal.CroupierShuffleNet;
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
import se.sics.kompics.network.Transport;
import se.sics.p2ptoolbox.util.network.NatedAddress;
import se.sics.p2ptoolbox.util.network.impl.BasicContentMsg;
import se.sics.p2ptoolbox.util.network.impl.BasicHeader;
import se.sics.p2ptoolbox.util.network.impl.BasicNatedAddress;
import se.sics.p2ptoolbox.util.network.impl.RelayHeader;
import se.sics.p2ptoolbox.util.network.impl.SourceHeader;

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
    
    NatedAddress next,prev,Parent;
    
    HashSet<NatedAddress> partners=new HashSet<NatedAddress>();
    HashSet<NatedAddress> freshpartners=new HashSet<NatedAddress>();
    
    HashMap<NatedAddress,NatedAddress> cashe=new HashMap<NatedAddress ,NatedAddress>();
    ArrayList<NatedAddress> fingers=new ArrayList<NatedAddress>();

	private Set<NatedAddress> opensample=new HashSet<NatedAddress>();

    public NatTraversalComp(NatTraversalInit init) 
    {
        this.selfAddress = init.selfAddress;
        this.selfHash=util.NATHash(selfAddress);
        log.info("{} {} initiating...", new Object[]{selfAddress.getId(), (selfAddress.isOpen() ? "OPEN" : "NATED")});

        this.rand = new Random(init.seed);
        
        for(int i=0;i<100;i++)
        	fingers.add(null);
        
        
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
            if(msg.getContent() instanceof Response)
            {
            	trigger(msg,local);
            	return;
            }//*/
            if(msg.getHeader() instanceof Container)//from routing
            {
            	Container<NatedAddress> cont=(Container<NatedAddress>)msg.getHeader();
            	//System.err.println("container? "+msg.getHeader().getClass().getName());
            	if(partners.contains(msg.getDestination()))
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

           
        		if(msg.getHeader() instanceof RelayHeader)
        		{
        			
        			RelayHeader<NatedAddress> h=(RelayHeader<NatedAddress>)msg.getHeader();
        			
        			System.err.println("hyrra a messege got thrue the nat :"+h);
        			trigger(msg.copyMessage(h.getActualHeader()),local);
        			return;
        		}
        		if(msg.getHeader() instanceof SourceHeader)
        		{
                	

        			SourceHeader<NatedAddress> h=(SourceHeader<NatedAddress>) msg.getHeader();
    				System.err.println("handle souceheader"+h);
        			BasicHeader<NatedAddress> b=new BasicHeader<NatedAddress>(h.getSource(),h.getActualDestination(),Transport.UDP);
        			Container<NatedAddress> cont=new Container<NatedAddress>(b, selfAddress, doRouting(h.getActualDestination()));
        			trigger(msg.copyMessage(cont),network);
        			partners.add(h.getSource());
        			return;
        		}

            	if(selfAddress.isOpen())
            	{
            		trigger(msg,local);
            	}
        		else
        		{
        			
        			System.err.println(msg.getContent().getClass().getSimpleName());
        			throw new RuntimeException("nat traversal logic error");
        		}

            	
            }
        }

	
     

    };
    private void useAddress(NatedAddress in)
    {
    	
    	if(in==null)
    		return;
    	if(next==null||getDistTo(in)<getDistTo(next))
    	{
    		next=in;
    		//System.err.println("use Adr:get next");
    	}
    	if(prev==null||getDistTo(in)>getDistTo(prev))
    	{
    		prev=in;
    		//System.err.println("use Adr:get prev");
    	}
    	
    	for(int i=1;i<fingers.size();i++)
    	{
    		//System.err.println("finger Set"+Math.pow(2, i)+" "+getDistTo(in)+" "+Math.pow(2, i-1));
    		if(Math.pow(2, i)>getDistTo(in)&&getDistTo(in)>Math.pow(2, i-1))
    		{
    			fingers.set(i,in);
    			//System.err.println("finger Set");
    		}
    	}
    	
    }
    private void spy(NetMsg<Object> msg)
	{
		if(msg.getSource().isOpen())
			useAddress(msg.getSource());
		if(msg.getHeader() instanceof Container)
		{
			
		}
		
	}
   
    
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
            	if(Parent!=null)
            	{
            		SourceHeader<NatedAddress> h=new SourceHeader<NatedAddress>(msg.getHeader(),getParent());
            		System.err.println("sending SourceHeader, "+h );
            		trigger(msg.copyMessage(h),network);
            	}
            	
            }
        }

	

    };

	private NatedAddress doRouting(NatedAddress dest)
	{
		
		if(dest==null)
			return null;
		if(dest.isOpen())
			return dest;
		NatedAddress ret=cashe.get(dest);
		if(ret!=null)
			return ret;
		else
		{
			ret=getPred(dest);
			if(ret!=null)
				return ret;
			else
				if(opensample.size()>0)
					return randomNode(opensample);
				else return next;
		}
	}
    protected NatedAddress getParent()
    {
		
		return Parent;
	}
	protected void join()
    {
		if(selfAddress.isOpen())
		{
			NatedAddress node=randomNode(opensample);

		}
		else
		{
			if(Parent==null)
			{
				NatedAddress node=randomNode(opensample);
				Parent=node;
				System.err.println("ass parent "+selfAddress+" : "+node);
			}
			
		}	
			
		
	}

	private Handler<PositionRequest> positionreq = new Handler<PositionRequest>() {
    	     	@Override
    	        public void handle(PositionRequest event)
    	     	{
    	     		if(!selfAddress.isOpen())
	     			{
    	     			if(event.getSource()==getParent())
	     				{
    	     				
    	     				if(getDistTo(event.getContent())<getDistTo(Parent))
    	     				{
    	     					Parent=event.getContent();
    	     					join();//get better parent if possible;
    	     				}
	     				}
    	     			else
    	     			{
    	     				throw new RuntimeException("nat traversal logic error");
    	     			}
	     			}
    	     		else
    	     		{
    	     			if(event.getContent()==null)
    	     			{
    	     				event.sendresp();
    	     			}
    	     			else
    	     			{
    	     				
    	     			}
    	     		}
    	     			
    	        }
    };

	private Handler<CroupierSample<NatedAddress>> handleCroupierSample = new Handler<CroupierSample<NatedAddress>>() {
    	     	@Override
    	        public void handle(CroupierSample<NatedAddress> event)
    	     	{
    	            log.info("{} croupier public nodes:{}", selfAddress.getBaseAdr(), event.publicSample);
    	            opensample=new HashSet<NatedAddress>();
    	            
    	            for(se.kth.swim.croupier.util.Container<NatedAddress, NatedAddress> a:event.publicSample)
    	            {
        	            //System.err.println(a.getSource());
    	            	useAddress(a.getSource());
    	            	opensample.add(a.getSource());
    	            }
    	            join();
    	            System.err.println("routeself "+selfAddress+" to "+doRouting(selfAddress)+" parent ="+getParent());
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
    	if(adr==null)
    		return null;
    	long delta=getDistTo(adr);
    	int best=0;
    	for(int i=1;i<fingers.size();i++)
    	{
    		if(fingers.get(i)==null)
    			continue;
    		long dist=getDistTo(fingers.get(i));
    		if(delta<=dist)
    			best++;
    		else
    			break;
        	System.err.println("fingers "+best+"/"+fingers.size());

    	}
    	return fingers.get(best);
    	
    }
    private class PositionRequest extends BasicContentMsg<NatedAddress, Header<NatedAddress>, NatedAddress>
    {

		public PositionRequest(Header<NatedAddress> header, NatedAddress content)
		{
			super(header, content);
			// TODO Auto-generated constructor stub
		}

		public void sendresp() 
		{
			BasicHeader<NatedAddress> h=new BasicHeader<NatedAddress>(selfAddress, this.getSource(),Transport.UDP);
			PositionRequest send=new PositionRequest(h,doRouting(this.getSource()));
		}
    	
    }
}
