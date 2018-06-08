package com.skcc.cloudz.zcp.namespace.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.simple.parser.ParseException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.skcc.cloudz.zcp.common.vo.RoleBindingVO;
import com.skcc.cloudz.zcp.manager.KeycloakManager;
import com.skcc.cloudz.zcp.manager.KubeAuthManager;
import com.skcc.cloudz.zcp.manager.KubeCoreManager;
import com.skcc.cloudz.zcp.namespace.vo.KubeDeleteOptionsVO;
import com.skcc.cloudz.zcp.namespace.vo.NamespaceVO;
import com.skcc.cloudz.zcp.user.vo.ServiceAccountVO;

import ch.qos.logback.classic.Logger;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ClusterRoleBinding;
import io.kubernetes.client.models.V1LimitRange;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.models.V1NamespaceSpec;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ResourceQuota;
import io.kubernetes.client.models.V1RoleRef;
import io.kubernetes.client.models.V1Subject;

@Service
public class NamespaceService {

	private final Logger LOG = (Logger) LoggerFactory.getLogger(NamespaceService.class);
	
	@Autowired
	KeycloakManager keycloakDao;
	
	@Autowired
	KubeCoreManager CoreMng;
	
	@Autowired
	KubeAuthManager AuthMng;
	
	@Value("${kube.cluster.role.binding.prefix}")
	String clusterRoleBindingPrefix;
	
	@Value("${kube.role.binding.prefix}")
	String roleBindingPrefix;
	
	@Value("${kube.service.account.prefix}")
	String serviceAccountPrefix;
	
	@Value("${kube.system.namespace}")
	String systemNamespace;
	
	
	public V1Namespace getNamespace(String namespace) throws ApiException, ParseException{
		try {
			return CoreMng.namespace(namespace);
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		return null;
	}
	
	public V1NamespaceList getNamespaceList() throws ApiException, ParseException{
		try {
			return CoreMng.namespaceList();
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		return null;
	}
	
	public NamespaceVO getNamespaceResource(String namespace) throws ApiException, ParseException{
		NamespaceVO vo = new NamespaceVO();
		V1ResourceQuota quota =  CoreMng.getQuota(namespace, namespace);
		V1LimitRange limitRanges =  CoreMng.getLimitRanges(namespace, namespace);
		vo.setLimitRange(limitRanges);
		vo.setResourceQuota(quota);
		
		return vo;
		
	}
	
	/**
	 * only name of namespaces
	 * @param namespace
	 * @return
	 * @throws ApiException
	 * @throws ParseException
	 */
	@SuppressWarnings(value= {"unchecked", "rawtypes"})
	@Deprecated
	public List<Map> getAllOfNamespace() throws ApiException, ParseException{
		List<Map> namespaceList = new ArrayList();
		V1NamespaceList map =  CoreMng.namespaceList();
		List<V1Namespace> item = (List<V1Namespace>) map.getItems();
		item.stream().forEach((data) ->{
			String name = data.getMetadata().getName();
			Map<String, String> mapNamespace = new HashMap();
			mapNamespace.put("name", name);
			namespaceList.add(mapNamespace);
		});
		return namespaceList;
	}
	
	
	/**
	 * 네임스페이스 생성 또는 변경
	 * @param namespacevo
	 * @param quotavo
	 * @param limitvo
	 * @throws ApiException
	 */
	public void createAndEditNamespace(NamespaceVO data) throws ApiException {
		V1ObjectMeta namespace_meta = new V1ObjectMeta();
		V1ObjectMeta quota_meta = new V1ObjectMeta();
		V1ObjectMeta limit_meta = new V1ObjectMeta();
		Map<String, String> labels = new HashMap<String, String>();
		labels.put("zcp-system-ns", "true");
		
		namespace_meta.setName(data.getNamespace());
		quota_meta.setName(data.getNamespace());
		limit_meta.setName(data.getNamespace());
		
		V1Namespace namespacevo = new V1Namespace();
		V1ResourceQuota quotavo = data.getResourceQuota();
		V1LimitRange limitvo = data.getLimitRange();
		namespacevo.setApiVersion("v1");
		namespacevo.setKind("Namespace");
		namespacevo.setSpec(new V1NamespaceSpec().addFinalizersItem("kubernetes"));
		namespacevo.setMetadata(namespace_meta);
		namespacevo.getMetadata().setLabels(labels);
		quotavo.setApiVersion("v1");
		quotavo.setKind("ResourceQuota");
		quotavo.setMetadata(quota_meta);
		limitvo.setApiVersion("v1");
		limitvo.setKind("LimitRange");
		limitvo.setMetadata(quota_meta);
		
		String namespace = data.getNamespace();
		try {
			CoreMng.createNamespace(namespace, namespacevo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				CoreMng.editNamespace(namespace, namespacevo);
			}else {
				throw e;	
			}
		}
		
		try {
			CoreMng.createLimitRanges(namespace, limitvo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				String name = limitvo.getMetadata().getName();
				CoreMng.editLimitRanges(namespace, name, limitvo);
			}else {
				throw e;	
			}
		}
		
		try {
			CoreMng.createQuota(namespace, quotavo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				String name = limitvo.getMetadata().getName();
				CoreMng.editQuota(namespace, name, quotavo);
			}else {
				throw e;	
			}
		}
	}
	
	
	
	public void deleteClusterRoleBinding(KubeDeleteOptionsVO data) throws IOException, ApiException{
		AuthMng.deleteClusterRoleBinding(data.getName(), data);
	}
	
	
	public void createRoleBinding(RoleBindingVO binding) throws IOException, ApiException{
		Map<String, String> labels = new HashMap<String, String>();
		labels.put("zcp-system-user", "true");
		labels.put("zcp-system-username", binding.getUserName());
		
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.setName(roleBindingPrefix + binding.getUserName());
		metadata.setLabels(labels);
		metadata.setNamespace(binding.getNamespace());
		
		V1Subject subject = new V1Subject();
		subject.setKind("ServiceAccount");
		subject.setName(serviceAccountPrefix + binding.getUserName());
		subject.setNamespace(systemNamespace);
		
		V1RoleRef roleRef = new V1RoleRef();
		roleRef.setApiGroup("rbac.authorization.k8s.io");
		roleRef.setKind("ClusterRole");
		roleRef.setName(binding.getClusterRole());
		
		List<V1Subject> subjects = new ArrayList<V1Subject>();

		binding.setApiVersion("rbac.authorization.k8s.io/v1");
		binding.setKind("RoleBinding");
		binding.setSubjects(subjects);
		binding.setRoleRef(roleRef);
		binding.setMetadata(metadata);
		
		subjects.add(subject);
		
		try {
			AuthMng.createRoleBinding(binding.getNamespace(), binding);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				LOG.debug("Conflict...");
			}else {
				throw e;	
			}
		}
		
	}
	
	public void deleteRoleBinding(KubeDeleteOptionsVO data) throws IOException, ApiException{
		try {
			AuthMng.deleteRoleBinding(data.getNamespace(), roleBindingPrefix + data.getUserName() , data);
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		
	}
	

	public void  createAndEditServiceAccount(String name, String namespace, ServiceAccountVO vo) throws ApiException {
		try {
			CoreMng.createServiceAccount(vo.getNamespace(), vo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				CoreMng.editServiceAccount(name, vo.getNamespace(), vo);
			}else {
				throw e;	
			}
		}
	}
	
	public void createAndEditClusterRoleBinding(String username, V1ClusterRoleBinding clusterRoleBinding) throws ApiException {
		try {
			AuthMng.createClusterRoleBinding( clusterRoleBinding, username);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				AuthMng.editClusterRoleBinding(clusterRoleBinding.getMetadata().getName(), clusterRoleBinding, username);
			}else {
				throw e;	
			}
		}
	}
	
}
