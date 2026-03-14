import { Routes, Route, Navigate } from 'react-router-dom';
import NavBar from './components/NavBar';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import TenantRegistrationPage from './pages/TenantRegistrationPage';
import TenantDashboardPage from './pages/TenantDashboardPage';
import PipelineListPage from './pages/PipelineListPage';
import PipelineEditorPage from './pages/PipelineEditorPage';
import PipelineDetailPage from './pages/PipelineDetailPage';
import DemoPipelinePage from './pages/DemoPipelinePage';

export default function App() {
  return (
    <div className="min-h-screen bg-gray-50">
      <NavBar />
      <main className="container mx-auto px-4 py-6">
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<TenantRegistrationPage />} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <TenantDashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/pipelines"
            element={
              <ProtectedRoute>
                <PipelineListPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/pipelines/new"
            element={
              <ProtectedRoute>
                <PipelineEditorPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/pipelines/:id/edit"
            element={
              <ProtectedRoute>
                <PipelineEditorPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/pipelines/:id"
            element={
              <ProtectedRoute>
                <PipelineDetailPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/demo"
            element={
              <ProtectedRoute>
                <DemoPipelinePage />
              </ProtectedRoute>
            }
          />
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </main>
    </div>
  );
}
