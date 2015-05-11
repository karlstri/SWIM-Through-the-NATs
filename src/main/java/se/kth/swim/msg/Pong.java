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
	NatedAddress sender;
	public HashMap<NatedAddress,Status> data;
	
	public Pong(NatedAddress sender,HashMap<NatedAddress,Status> piggy )
	{
		this.sender=sender;
		data=piggy;
	}
}
