package se.kth.swim.msg;

import java.io.Serializable;
import java.util.HashMap;

import se.sics.p2ptoolbox.util.network.NatedAddress;

public class Pong implements Serializable 
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 8445207338053984124L;
	public NatedAddress sender;
	public NatedAddress Proxy;
	public NatedAddress dest;

	public HashMap<NatedAddress,Status> data;
	
	public Pong(NatedAddress sender,NatedAddress dest,NatedAddress proxy,HashMap<NatedAddress,Status> piggy )
	{
		this.sender=sender;
		this.dest=dest;
		this.Proxy=proxy;
		data=piggy;
	}

	public Pong(Ping ping, HashMap<NatedAddress, Status> gossip)
	{
		this.sender=ping.dest;
		this.dest=ping.sender;
		this.Proxy=ping.Proxy;
		data=gossip;
	}
}
