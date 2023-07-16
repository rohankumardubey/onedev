package io.onedev.server.entitymanager.impl;

import com.google.common.collect.Sets;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.AlertManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.Listen;
import io.onedev.server.event.cluster.ConnectionLost;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.mail.MailManager;
import io.onedev.server.model.Alert;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.BaseEntityManager;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.web.util.WicketUtils;
import io.onedev.server.web.websocket.WebSocketManager;
import org.hibernate.criterion.Restrictions;
import org.unbescape.html.HtmlEscape;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Date;

import static io.onedev.server.model.Alert.PROP_SUBJECT;

@Singleton
public class DefaultAlertManager extends BaseEntityManager<Alert> implements AlertManager {
	
	private final MailManager mailManager;
	
	private final ClusterManager clusterManager;
	
	private final WebSocketManager webSocketManager;
	
	private final SettingManager settingManager;
	
	private final TransactionManager transactionManager;
	
	private final SessionManager sessionManager;
	
	private final UserManager userManager;
	
	@Inject
	public DefaultAlertManager(Dao dao, MailManager mailManager, ClusterManager clusterManager, 
							   WebSocketManager webSocketManager, SettingManager settingManager, 
							   TransactionManager transactionManager, SessionManager sessionManager, 
							   UserManager userManager) {
		super(dao);
		this.mailManager = mailManager;
		this.clusterManager = clusterManager;
		this.webSocketManager = webSocketManager;
		this.settingManager = settingManager;
		this.transactionManager = transactionManager;
		this.sessionManager = sessionManager;
		this.userManager = userManager;
	}
	
	@Transactional
	@Override
	public void alert(String subject, String detail, boolean mailError) {
		var criteria = newCriteria();
		criteria.add(Restrictions.eq(PROP_SUBJECT, subject));
		var alert = find(criteria);
		if (alert == null) {
			alert = new Alert();
			alert.setSubject(subject);
			alert.setDetail(detail);
			alert.setMailError(mailError);
		}
		alert.setDate(new Date());
		dao.persist(alert);
	}

	@Listen
	public void on(ConnectionLost event) {
		if (clusterManager.isLeaderServer()) {
			var server = event.getServer();
			alert("Server '" + server + "' can not be reached", 
					"Server '" + server + "' is part of OneDev cluster, but can not be reached for some reason", 
					false);
		}
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		if (event.getEntity() instanceof Alert) {
			var alert = (Alert)event.getEntity();			
			if (!alert.isMailError()) {
				var alertSetting = settingManager.getAlertSetting();
				if (!alertSetting.getNotifyUsers().isEmpty()) {
					transactionManager.runAfterCommit(() -> sessionManager.runAsync(() -> {
						var mailSetting = settingManager.getMailSetting();
						if (mailSetting == null) {
							alert("Unable to send alert email: Mail setting not defined yet", null, true);
						} else {
							var emailAddresses = new ArrayList<String>();
							for (var userName: alertSetting.getNotifyUsers()) {
								var user = userManager.findByName(userName);
								if (user == null) {
									alert("Unable to send alert email: User '" + userName + "' does not exist", null, true);
								} else {
									var emailAddress = user.getPrimaryEmailAddress();
									if (emailAddress == null) {
										alert("Unable to send alert email: Primary email address of user "
												+ userName + " is not defined", null, true);
									} else if (!emailAddress.isVerified()) {
										alert("Unable to send alert email: Primary email address of user "
												+ userName + " is not verified yet", null, true);
									} else {
										emailAddresses.add(emailAddress.getValue());
									}
								}
							}
							if (!emailAddresses.isEmpty()) {
								String url = settingManager.getSystemSetting().getServerUrl();
								String htmlBody, textBody;
								if (alert.getDetail() != null) {
									htmlBody = String.format(""
												+ "OneDev URL: <a href='%s'>%s</a>"
												+ "<p style='margin: 16px 0;'>"
												+ "<b>Error detail:</b>"
												+ "<pre style='font-family: monospace;'>%s</pre>",
											url, url, HtmlEscape.escapeHtml5(alert.getDetail()));
									textBody = String.format(""
												+ "URL: %s\n\n"
												+ "Error detail:\n"
												+ "%s",
											url, alert.getDetail());
								} else {
									htmlBody = String.format("OneDev URL: <a href='%s'>%s</a>", url, url);
									textBody = String.format("OneDev url: %s", url);
								}
								
								mailManager.sendMail(mailSetting.getSendSetting(), emailAddresses, new ArrayList<>(), new ArrayList<>(), 
										"[Alert] " + alert.getSubject(), htmlBody, textBody, null, null, null);
							}
						}
					}));
				}
			}
		}
	}

	@Override
	public int count() {
		return count(true);
	}

	@Transactional
	@Override
	public void clear() {
		var query = getSession().createQuery("delete from Alert");
		query.executeUpdate();
		webSocketManager.notifyObservablesChange(Sets.newHashSet(Alert.getChangeObservable()), WicketUtils.getPageKey());		
	}
	
}