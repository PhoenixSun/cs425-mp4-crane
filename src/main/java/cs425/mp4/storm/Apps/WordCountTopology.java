/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cs425.mp4.storm.Apps;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.topology.TopologyBuilder;

/**
 * word counting topology
 */
public class WordCountTopology {

  public static void main(String[] args) throws Exception {
	  
	if(args.length!=2){
		System.err.println("[ERROR]: Missing Argument(s)");
		System.err.println("Usage: WordCountTopology filename dashboradHost");
		System.exit(-1);
	}
	else{
	    TopologyBuilder builder = new TopologyBuilder();
	
	    builder.setSpout("spout", new FileReaderSpout(args[0]), 1);
	    builder.setBolt("lcount", new LocalCountBolt(), 2).shuffleGrouping("spout");
	    builder.setBolt("filter", new IgnoreStopWordsBolt(), 2).shuffleGrouping("lcount");
	    builder.setBolt("dashboard", new DashboardPrinterBolt(args[1],8888),2).shuffleGrouping("filter");
	    Config conf = new Config();
	    conf.setDebug(true);
	    conf.setNumWorkers(3);
	    StormSubmitter.submitTopologyWithProgressBar("WordCount", conf, builder.createTopology());
	  }
  }
}
