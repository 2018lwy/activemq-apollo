/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.fusesource.scalate.RenderContext

package

/**
 * <p>
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
object Website {

  val project_name= "Apollo"
  val project_slogan= "AcitveMQ's next generation of messaging"
  val project_id= "apollo"
  val project_jira_key= "APLO"
  val project_issue_url= "https://issues.apache.org/jira/browse/APLO"
  val project_forums_url= "http://activemq.2283324.n4.nabble.com/ActiveMQ-Dev-f2368404.html"
  val project_wiki_url= "https://cwiki.apache.org/confluence/display/ACTIVEMQ/Index"
  val project_logo= "/images/project-logo.png"
  val project_version= "1.1"
  val project_snapshot_version= "1.2-SNAPSHOT"
  val project_versions = List(
        project_version,
        "1.0",
        "1.0-beta6",
        "1.0-beta5",
        "1.0-beta4",
        "1.0-beta3",
        "1.0-beta2",
        "1.0-beta1"
        )  

  val project_keywords= "messaging,stomp,jms,activemq,apollo"

  // -------------------------------------------------------------------
  val project_svn_url= "http://svn.apache.org/repos/asf/activemq/activemq-apollo"
  val project_svn_trunk_url= project_svn_url +"/trunk"
  val project_svn_branches_url= project_svn_url + "/branches"
  val project_svn_tags_url= project_svn_url + "/tags"
  val project_svn_commiter_url= project_svn_trunk_url.replaceFirst("http","https")
  
  val project_maven_groupId= "org.apache.activemq"
  val project_maven_artifactId= "apollo-broker"

  val website_base_url= "http://activemq.apache.org/apollo"
}