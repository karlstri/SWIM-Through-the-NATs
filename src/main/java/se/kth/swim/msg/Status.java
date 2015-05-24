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

package se.kth.swim.msg;

import java.io.Serializable;

/**
 * @author Alex Ormenisan <aaor@sics.se>
 */
public class Status implements Serializable
{
    /**
	 * 
	 */
	private static final long serialVersionUID = -1322131551168831635L;
	public static final int ALIVE = 0;
	public static final int DEAD = 4;
	public static final int SUSP = 1;

	public int Status;
    public long time;
	public int receivedPings;
    
  

	public Status(int s, long ts)
	{
		Status=s;
		time=ts;
	}



	public Status(int receivedPings2) {
		this.receivedPings=receivedPings2;
	}



	public boolean isAlive()
	{
		if(Status ==ALIVE)
			return true;
		return false;
	}



	public boolean isSusp() {
		if(Status ==SUSP)
			return true;
		return false;
	}



	public boolean isDead() {
		if(Status ==DEAD)
			return true;
		return false;
	}
}
